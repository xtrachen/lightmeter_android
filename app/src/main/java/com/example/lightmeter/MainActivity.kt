package com.example.lightmeter

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import com.example.lightmeter.ui.theme.LightMeterTheme
import kotlin.math.abs

class MainActivity : ComponentActivity() {

    private lateinit var sensorManager: SensorManager
    private var lightSensor: Sensor? = null

    private var lightLevelState by mutableFloatStateOf(0f)
    private val cameraLightLevelLiveData = MutableLiveData<Float>(0f)

    private val lightSensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_LIGHT) {
                lightLevelState = event.values.firstOrNull() ?: 0f
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            // 不需要处理精度变化
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

        setContent {
            LightMeterTheme {
                val context = LocalContext.current
                var useCamera by remember { mutableStateOf(false) }
                var useFrontCamera by remember { mutableStateOf(false) }
                var cameraLight by remember { mutableFloatStateOf(0f) }

                DisposableEffect(Unit) {
                    val observer = Observer<Float> { value ->
                        cameraLight = value
                    }
                    cameraLightLevelLiveData.observeForever(observer)
                    onDispose {
                        cameraLightLevelLiveData.removeObserver(observer)
                    }
                }

                LightMeterScreen(
                    useCamera = useCamera,
                    useFrontCamera = useFrontCamera,
                    sensorLight = lightLevelState,
                    cameraLight = cameraLight,
                    onToggleMode = { useCamera = !useCamera },
                    onToggleCameraLens = { useFrontCamera = !useFrontCamera },
                    context = context
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lightSensor?.also { sensor ->
            sensorManager.registerListener(
                lightSensorListener,
                sensor,
                SensorManager.SENSOR_DELAY_NORMAL
            )
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(lightSensorListener)
    }

    fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun requestCameraPermission() {
        if (!hasCameraPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                1001
            )
        }
    }

    fun startCameraAnalysis(context: Context, useFrontCamera: Boolean) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(
                    if (useFrontCamera) CameraSelector.LENS_FACING_FRONT
                    else CameraSelector.LENS_FACING_BACK
                )
                .build()

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(mainExecutor) { image: ImageProxy ->
                        val avgLuma = estimateLuma(image)
                        cameraLightLevelLiveData.postValue(avgLuma)
                        image.close()
                    }
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    imageAnalyzer
                )
            } catch (exc: Exception) {
                Log.e("LightMeter", "Camera binding failed", exc)
            }
        }, mainExecutor)
    }

    private fun estimateLuma(image: ImageProxy): Float {
        val buffer = image.planes[0].buffer
        buffer.rewind()
        var sum = 0L
        val remaining = buffer.remaining()
        val data = ByteArray(remaining)
        buffer.get(data)
        for (b in data) {
            sum += (b.toInt() and 0xFF)
        }
        return if (remaining > 0) sum.toFloat() / remaining else 0f
    }
}

@Composable
fun LightMeterScreen(
    useCamera: Boolean,
    useFrontCamera: Boolean,
    sensorLight: Float,
    cameraLight: Float,
    onToggleMode: () -> Unit,
    onToggleCameraLens: () -> Unit,
    context: Context,
    modifier: Modifier = Modifier
) {
    val activity = context as? MainActivity

    // ISO 与光圈选项
    val isoOptions = listOf(25, 50, 80, 100, 200, 400, 500, 800)
    val apertureOptions = listOf(1.0f, 1.1f, 1.2f, 1.4f, 1.8f, 2.0f, 2.4f, 2.8f, 3.2f, 3.5f, 4.0f, 5.6f, 6.2f, 7.0f, 8.0f)

    var isoIndex by remember { mutableStateOf(3) }        // 默认 ISO 100
    var apertureIndex by remember { mutableStateOf(7) }   // 默认 F2.8

    val currentIso = isoOptions[isoIndex]
    val currentAperture = apertureOptions[apertureIndex]

    val brightness = if (useCamera) cameraLight else sensorLight
    val shutterSeconds = calculateShutterSpeedSeconds(
        iso = currentIso,
        aperture = currentAperture,
        brightness = brightness
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Button(onClick = {
            onToggleMode()
        }) {
            Text(text = if (useCamera) "切换到环境光传感器" else "切换到摄像头测光")
        }

        if (useCamera) {
            Button(
                onClick = { onToggleCameraLens() },
                modifier = Modifier.padding(top = 16.dp)
            ) {
                Text(text = if (useFrontCamera) "当前：前置摄像头（点此切换后置）" else "当前：后置摄像头（点此切换前置）")
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            if (useCamera) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "摄像头亮度估计：${"%.2f".format(cameraLight)}")
                    Text(text = "推荐快门：${formatShutterSpeed(shutterSeconds)}")
                }
                LaunchedEffect(useCamera, useFrontCamera) {
                    if (activity?.hasCameraPermission() == true) {
                        activity.startCameraAnalysis(context, useFrontCamera = useFrontCamera)
                    } else {
                        activity?.requestCameraPermission()
                    }
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "环境光亮度：${"%.2f".format(sensorLight)} lx")
                    Text(text = "推荐快门：${formatShutterSpeed(shutterSeconds)}")
                }
            }
        }

        // 底部 ISO / 光圈 选择器
        Column {
            Text(
                text = "ISO：$currentIso",
                modifier = Modifier.padding(top = 8.dp)
            )
            Row(
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Button(
                    onClick = {
                        isoIndex = (isoIndex - 1 + isoOptions.size) % isoOptions.size
                    },
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Text(text = "上一档")
                }
                Button(
                    onClick = {
                        isoIndex = (isoIndex + 1) % isoOptions.size
                    }
                ) {
                    Text(text = "下一档")
                }
            }

            Text(
                text = "光圈：F${"%.1f".format(currentAperture)}",
                modifier = Modifier.padding(top = 16.dp)
            )
            Row(
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Button(
                    onClick = {
                        apertureIndex = (apertureIndex - 1 + apertureOptions.size) % apertureOptions.size
                    },
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Text(text = "上一档")
                }
                Button(
                    onClick = {
                        apertureIndex = (apertureIndex + 1) % apertureOptions.size
                    }
                ) {
                    Text(text = "下一档")
                }
            }
        }
    }
}

/**
 * 根据 ISO、光圈和当前亮度估算快门速度（秒）。
 * 这里只是一个近似模型，用于演示：亮度越高 / ISO 越大 / 光圈越大，快门越快。
 */
fun calculateShutterSpeedSeconds(iso: Int, aperture: Float, brightness: Float): Float {
    if (brightness <= 0.1f) return 30f // 亮度太低时给一个很慢的快门

    val effectiveBrightness = brightness + 1f
    val t = (aperture * aperture * 100f) / (iso * effectiveBrightness)

    // 限制在 1/8000s ~ 30s 范围内
    return t.coerceIn(1f / 8000f, 30f)
}

/**
 * 把秒数格式化成类似相机的快门表示，如 1/125s、0.5s 等。
 */
fun formatShutterSpeed(seconds: Float): String {
    if (seconds <= 0f) return "--"

    if (seconds < 1f) {
        val candidates = listOf(8000, 4000, 2000, 1000, 500, 250, 180, 125, 90, 60, 45, 30, 15, 8, 4, 2, 1)
        val best = candidates.minByOrNull { denom ->
            abs(1f / denom - seconds)
        } ?: 1
        return "1/$best s"
    }

    val rounded = if (seconds < 10f) {
        String.format("%.1f", seconds)
    } else {
        seconds.toInt().toString()
    }
    return "$rounded s"
}

@Preview(showBackground = true)
@Composable
fun LightLevelPreview() {
    LightMeterTheme {
        LightMeterScreen(
            useCamera = false,
            useFrontCamera = false,
            sensorLight = 0f,
            cameraLight = 0f,
            onToggleMode = {},
            onToggleCameraLens = {},
            context = LocalContext.current
        )
    }
}