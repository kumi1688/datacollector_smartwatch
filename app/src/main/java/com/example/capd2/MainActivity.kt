package com.example.capd2

import com.example.capd2.databinding.ActivityMainBinding

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.health.connect.client.records.OxygenSaturationRecord
import com.google.firebase.ktx.Firebase

import java.io.File
import java.io.IOException

import io.socket.client.IO
import io.socket.client.Socket

import com.google.firebase.storage.ktx.storage
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

import java.net.URISyntaxException

import java.time.LocalDate
import java.time.LocalDateTime

class MainActivity : Activity(), SensorEventListener {
    // 스마트워치 UI에 나타나는 텍스트 바인딩
    private lateinit var binding: ActivityMainBinding

    // 센서를 총 관리하는 변수
    private lateinit var sensorManager: SensorManager

    // 시작여부
    private var isStart = false

    // 센서 선언부분
    private var accelerationSensor: Sensor? = null
    private var hrateSensor: Sensor? = null
    private var oxygenSaturationRecord: OxygenSaturationRecord? = null

    // 센서 리스트
    private var accList = mutableListOf<AccData>()
    private var hrateList = mutableListOf<HeartRateData>()

    // 오디오
    private lateinit var recordFile: File
    private var mediaRecorder: MediaRecorder? = null

    // REST API 호출시 필요한 부분
    private val apis = Apis.create()

    // 기타 옵션
    private var startAt: Long = 0
    private var endAt: Long = 0
    private var createdDateTime: Long = 0
    private var collectId: Long = 0

    // 데이터 수집에 필요한 권한 목록
    private var requiredPermission = arrayOf(
        android.Manifest.permission.BODY_SENSORS,
        android.Manifest.permission.RECORD_AUDIO,
    )

    // 웹소켓
    private var mSocket: Socket? = null

    // Firebase 설정
    private val storage = Firebase.storage

    // 프로그램이 시작하는 경우 시작하는 함수 (main)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        var isGranted = true;
        for (permission in requiredPermission) {
            if (checkSelfPermission(permission) == PackageManager.PERMISSION_DENIED) {
                isGranted = false;
                break;
            }
        }
        if (!isGranted) {
            requestPermissions(requiredPermission, 0);
        } else {
            init()
        }

        binding.button.setOnClickListener {
            if (isStart) {
                // 데이터 수집 끝
                isStart = false
                endAt = System.currentTimeMillis()
                // 녹음 끝
                stopRecord()

                // 웹소켓 연결종료
                mSocket!!.close()

                binding.button.text = getString(R.string.start)
                binding.button.backgroundTintList = ColorStateList.valueOf(getColor(androidx.wear.R.color.circular_progress_layout_blue))
//                binding.status.text = getString(R.string.wait_status)
                binding.mic.text = getString(R.string.wait_mic)
                binding.xValue.text = getString(R.string.wait_value)
                binding.yValue.text = getString(R.string.wait_value)
                binding.zValue.text = getString(R.string.wait_value)

                val body = UploadBody(accList, startAt, endAt, "SENSOR_DELAY_GAME", createdDateTime, "smartwatch", collectId)
                uploadDataFirebase(body)
                uploadMicFirebase(recordFile)
                apis.uploadData(body).enqueue(object: Callback<UploadResponse> {
                    override fun onResponse(
                        call: Call<UploadResponse>,
                        response: Response<UploadResponse>
                    ) {
                        print("데이터 전송 성공")
                    }
                    override fun onFailure(call: Call<UploadResponse>, t: Throwable) {
                        print("데이터 전송 실패")
                    }
                })

                val tag = "stop"
//                Log.d(tag, "" + body)
                Log.d(tag, "deviceId : " + Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID))
                Log.d(tag, "android : " + android.os.Build.VERSION.SDK_INT)

            } else {
                // 수집 시작
                binding.button.text = getString(R.string.stop)
                binding.button.backgroundTintList = ColorStateList.valueOf(getColor(androidx.wear.R.color.circular_progress_layout_red))
//                binding.status.text = getString(R.string.run_status)
                binding.mic.text = getString(R.string.run_mic)

                // 녹음시작
                startRecord()

                // 웹 소켓 연결
                initSocket();

                accList.clear()
                hrateList.clear()

                collectId = System.currentTimeMillis()
                createdDateTime = System.currentTimeMillis()
                startAt = System.currentTimeMillis()
                isStart = true
            }
        }
    }

    override fun onResume() {
//        val samplingPeriodUs = SensorManager.SENSOR_DELAY_FASTEST;
        val samplingPeriodUs = SensorManager.SENSOR_DELAY_GAME;

        super.onResume()
        sensorManager.apply {
            registerListener(this@MainActivity, accelerationSensor, samplingPeriodUs)
            registerListener(this@MainActivity, hrateSensor, samplingPeriodUs)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    /*
        Sensor
     */
    private fun init() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager.apply {
            accelerationSensor = getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            hrateSensor = getDefaultSensor(Sensor.TYPE_HEART_RATE)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event != null) {
            when(event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> getAccelerometerData(event)
                Sensor.TYPE_HEART_RATE -> getHrateData(event)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    }

    // 가속도 데이터 센서값을 받아들이는 함수
    private fun getAccelerometerData(event: SensorEvent) {
        val tag:String = "Accelerometer"
        val axisX: Float = event.values[0]
        val axisY: Float = event.values[1]
        val axisZ: Float = event.values[2]
        if (isStart) {
            binding.xValue.text = axisX.toString()
            binding.yValue.text = axisY.toString()
            binding.zValue.text = axisZ.toString()

            val accData = AccData(accList.size, axisX, axisY, axisZ, System.currentTimeMillis())
            mSocket!!.emit("acc", accData.toString())
            accList.add(accData)
        }
    }

    // 심박수 데이터 센서값을 받아들이는 함수
    private fun getHrateData(event: SensorEvent){
        val tag:String = "Hrate"
        val hrate:Float = event.values[0]
//        Log.d("", hrate.toString())
        if(isStart){
            binding.hrate.text = hrate.toString()

            val hrateData = HeartRateData(hrateList.size, hrate, System.currentTimeMillis())
            hrateList.add(hrateData)
            mSocket!!.emit("hrate", hrate.toString())
        }
    }

    private fun startRecord() {
        val tag = "startRecord"
        val time = System.currentTimeMillis()
        recordFile = File.createTempFile("$time", ".mp3", cacheDir)
        Log.d(tag, recordFile.absolutePath)
        mediaRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(recordFile)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            try {
                prepare()
            } catch (e: IOException) {
                Log.e(tag, "prepare() fail")
            }
            start()
        }
    }

    private fun stopRecord() {
        val tag = "stopRecord"
        mediaRecorder?.apply {
            try {
                stop()
                reset()
                release()
            } catch (e: IOException) {
                Log.e(tag, e.stackTraceToString())
            }
            mediaRecorder = null
        }
    }

    private fun uploadMicFirebase(data: File){
        val storageRef = storage.reference
        val collectTime = LocalDateTime.now().toString().replace(":", "-")
        val pathString = "mic/" + collectTime + ".mp3"
        val pathRef = storageRef.child(pathString)

        var uploadTask = pathRef.putFile(Uri.fromFile(data))
        uploadTask.addOnFailureListener{
            Log.d("fb", "파이어베이스 전송 실패")
        }.addOnSuccessListener {
            Log.d("fb", "파이어베이스 전송 성공 ")
        }
    }


    private fun uploadDataFirebase(body: UploadBody){
        // Create a storage reference from our app
        val storageRef = storage.reference
        val collectTime = LocalDateTime.now().toString().replace(":", "-")
        val pathString = "data/" + collectTime + ".json"
        val pathRef = storageRef.child(pathString)

        val gson: Gson = GsonBuilder().setLenient().create()
        var data = gson.toJson(body).toByteArray()
        Log.d("데이터", data.toString())
        var uploadTask = pathRef.putBytes(data)
        uploadTask.addOnFailureListener{
            Log.d("fb", "파이어베이스 전송 실패")
        }.addOnSuccessListener {
            Log.d("fb", "파이어베이스 전송 성공 ")
        }
    }

    private fun initSocket() {
        try {
            mSocket = IO.socket(env().WEBSOCKET_URL)
            Log.d("SOCKET", "Connection success : ")
        } catch (e: URISyntaxException) {
            e.printStackTrace()
        }
        mSocket!!.connect()
    }

}