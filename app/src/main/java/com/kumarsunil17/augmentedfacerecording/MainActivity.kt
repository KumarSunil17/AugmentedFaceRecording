package com.kumarsunil17.augmentedfacerecording

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.ar.core.*
import com.google.ar.core.ArCoreApk.InstallStatus
import com.google.ar.core.AugmentedFace.RegionType
import com.google.ar.core.Config.AugmentedFaceMode
import com.google.ar.core.Session.Feature
import com.google.ar.core.exceptions.*
import com.kumarsunil17.augmentedfacerecording.common.helpers.*
import com.kumarsunil17.augmentedfacerecording.common.rendering.BackgroundRenderer
import com.kumarsunil17.augmentedfacerecording.common.rendering.ObjectRenderer
import java.io.File
import java.io.IOException
import java.util.*
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MainActivity : AppCompatActivity() , GLSurfaceView.Renderer {
    private val TAG: String = MainActivity::class.java.simpleName
    private lateinit var surfaceView: GLSurfaceView
    private lateinit var recordButton: FloatingActionButton

    private var installRequested = false

    private var session: Session? = null
    private val messageSnackbarHelper: SnackbarHelper = SnackbarHelper()
    private var displayRotationHelper: DisplayRotationHelper? = null
    private val trackingStateHelper: TrackingStateHelper = TrackingStateHelper(this)

    private val backgroundRenderer: BackgroundRenderer = BackgroundRenderer()
    private val augmentedFaceRenderer = AugmentedFaceRenderer()
    private val noseObject: ObjectRenderer = ObjectRenderer()
    private val rightEarObject: ObjectRenderer = ObjectRenderer()
    private val leftEarObject: ObjectRenderer = ObjectRenderer()

    // Temporary matrix allocated here to reduce number of allocations for each frame.
    private val noseMatrix = FloatArray(16)
    private val rightEarMatrix = FloatArray(16)
    private val leftEarMatrix = FloatArray(16)
    private val DEFAULT_COLOR = floatArrayOf(0f, 0f, 0f, 0f)

    private var isRecording:Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        surfaceView = findViewById(R.id.gl_gurfaceview)
        recordButton = findViewById(R.id.record)
        recordButton.setOnClickListener(this::toggleRecording)
        displayRotationHelper = DisplayRotationHelper( /*context=*/this)

        // Set up renderer.
        surfaceView.preserveEGLContextOnPause = true
        surfaceView.setEGLContextClientVersion(2)
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0) // Alpha used for plane blending.

        surfaceView.setRenderer(this)

        surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        surfaceView.setWillNotDraw(false)
        installRequested = false
    }

    override fun onDestroy() {
        if (session != null) {
            // Explicitly close ARCore Session to release native resources.
            // Review the API reference for important considerations before calling close() in apps with
            // more complicated lifecycle requirements:
            // https://developers.google.com/ar/reference/java/arcore/reference/com/google/ar/core/Session#close()
            session!!.close()
            session = null
        }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (session == null) {
            var exception: Exception? = null
            var message: String? = null
            try {
                when (ArCoreApk.getInstance()?.requestInstall(this, !installRequested)) {
                    InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        return
                    }
                    InstallStatus.INSTALLED -> {
                    }
                }

                // ARCore requires camera permissions to operate. If we did not yet obtain runtime
                // permission on Android M and above, now is a good time to ask the user for it.
                if (!CameraPermissionHelper.hasCameraPermission(this)) {
                    CameraPermissionHelper.requestCameraPermission(this)
                    return
                }
                // Configure session to use front facing camera.
                val featureSet = EnumSet.of(Feature.FRONT_CAMERA)
                // Create the session.
                session = Session( /* context= */this, featureSet)
                configureSession()
            } catch (e: UnavailableArcoreNotInstalledException) {
                message = "Please install ARCore"
                exception = e
            } catch (e: UnavailableUserDeclinedInstallationException) {
                message = "Please install ARCore"
                exception = e
            } catch (e: UnavailableApkTooOldException) {
                message = "Please update ARCore"
                exception = e
            } catch (e: UnavailableSdkTooOldException) {
                message = "Please update this app"
                exception = e
            } catch (e: UnavailableDeviceNotCompatibleException) {
                message = "This device does not support AR"
                exception = e
            } catch (e: Exception) {
                message = "Failed to create AR session"
                exception = e
            }
            if (message != null) {
                messageSnackbarHelper.showError(this, message)
                Log.e(
                        TAG,
                        "Exception creating session",
                        exception
                )
                return
            }
        }

        // Note that order matters - see the note in onPause(), the reverse applies here.
        try {
            session!!.resume()
        } catch (e: CameraNotAvailableException) {
            messageSnackbarHelper.showError(this, "Camera not available. Try restarting the app.")
            session = null
            return
        }
        surfaceView.onResume()
        displayRotationHelper!!.onResume()
    }

    override fun onPause() {
        super.onPause()
        if (session != null) {
            // Note that the order matters - GLSurfaceView is paused first so that it does not try
            // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
            // still call session.update() and get a SessionPausedException.
            displayRotationHelper!!.onPause()
            surfaceView.onPause()
            session!!.pause()
        }
    }

    override fun onRequestPermissionsResult(
            requestCode: Int,
            permissions: Array<String?>,
            results: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
            Toast.makeText(
                    this,
                    "Camera permission is needed to run this application",
                    Toast.LENGTH_LONG
            )
                    .show()
            if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
                // Permission denied with checking "Do not ask again".
                CameraPermissionHelper.launchPermissionSettings(this)
            }
            finish()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
        // Prepare the rendering objects. This involves reading shaders, so may throw an IOException.
        try {
            // Create the texture and pass it to ARCore session to be filled during update().
            backgroundRenderer.createOnGlThread( /*context=*/this)
            augmentedFaceRenderer.createOnGlThread(this, "models/freckles.png")
            augmentedFaceRenderer.setMaterialProperties(0.0f, 1.0f, 0.1f, 6.0f)
            noseObject.createOnGlThread( /*context=*/
                    this,
                    "models/nose.obj",
                    "models/nose_fur.png"
            )
            noseObject.setMaterialProperties(0.0f, 1.0f, 0.1f, 6.0f)
            noseObject.setBlendMode(ObjectRenderer.BlendMode.AlphaBlending)
            rightEarObject.createOnGlThread(
                    this, "models/forehead_right.obj", "models/ear_fur.png"
            )
            rightEarObject.setMaterialProperties(0.0f, 1.0f, 0.1f, 6.0f)
            rightEarObject.setBlendMode(ObjectRenderer.BlendMode.AlphaBlending)
            leftEarObject.createOnGlThread(
                    this, "models/forehead_left.obj", "models/ear_fur.png"
            )
            leftEarObject.setMaterialProperties(0.0f, 1.0f, 0.1f, 6.0f)
            leftEarObject.setBlendMode(ObjectRenderer.BlendMode.AlphaBlending)
        } catch (e: IOException) {
            Log.e(
                    TAG,
                    "Failed to read an asset file",
                    e
            )
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        displayRotationHelper!!.onSurfaceChanged(width, height)
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        // Clear screen to notify driver it should not load any pixels from previous frame.
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (session == null) {
            return
        }
        // Notify ARCore session that the view size changed so that the perspective matrix and
        // the video background can be properly adjusted.
        displayRotationHelper!!.updateSessionIfNeeded(session)
        try {
            session!!.setCameraTextureName(backgroundRenderer.textureId)

            // Obtain the current frame from ARSession. When the configuration is set to
            // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
            // camera framerate.
            val frame = session!!.update()
            val camera = frame.camera

            // Get projection matrix.
            val projectionMatrix = FloatArray(16)
            camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)

            // Get camera matrix and draw.
            val viewMatrix = FloatArray(16)
            camera.getViewMatrix(viewMatrix, 0)

            // Compute lighting from average intensity of the image.
            // The first three components are color scaling factors.
            // The last one is the average pixel intensity in gamma space.
            val colorCorrectionRgba = FloatArray(4)
            frame.lightEstimate.getColorCorrection(colorCorrectionRgba, 0)

            // If frame is ready, render camera preview image to the GL surface.
            backgroundRenderer.draw(frame)

            // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
            trackingStateHelper.updateKeepScreenOnFlag(camera.trackingState)

            // ARCore's face detection works best on upright faces, relative to gravity.
            // If the device cannot determine a screen side aligned with gravity, face
            // detection may not work optimally.
            val faces = session!!.getAllTrackables(
                    AugmentedFace::class.java
            )
            for (face in faces) {
                if (face.trackingState != TrackingState.TRACKING) {
                    break
                }
                val scaleFactor = 1.0f

                // Face objects use transparency so they must be rendered back to front without depth write.
                GLES20.glDepthMask(false)

                // Each face's region poses, mesh vertices, and mesh normals are updated every frame.

                // 1. Render the face mesh first, behind any 3D objects attached to the face regions.
                val modelMatrix = FloatArray(16)
                face.centerPose.toMatrix(modelMatrix, 0)
                augmentedFaceRenderer.draw(
                        projectionMatrix, viewMatrix, modelMatrix, colorCorrectionRgba, face
                )

                // 2. Next, render the 3D objects attached to the forehead.
                face.getRegionPose(RegionType.FOREHEAD_RIGHT).toMatrix(rightEarMatrix, 0)
                rightEarObject.updateModelMatrix(rightEarMatrix, scaleFactor)
                rightEarObject.draw(
                        viewMatrix,
                        projectionMatrix,
                        colorCorrectionRgba, DEFAULT_COLOR
                )
                face.getRegionPose(RegionType.FOREHEAD_LEFT).toMatrix(leftEarMatrix, 0)
                leftEarObject.updateModelMatrix(leftEarMatrix, scaleFactor)
                leftEarObject.draw(
                        viewMatrix,
                        projectionMatrix,
                        colorCorrectionRgba, DEFAULT_COLOR
                )

                // 3. Render the nose last so that it is not occluded by face mesh or by 3D objects attached
                // to the forehead regions.
                face.getRegionPose(RegionType.NOSE_TIP).toMatrix(noseMatrix, 0)
                noseObject.updateModelMatrix(noseMatrix, scaleFactor)
                noseObject.draw(
                        viewMatrix,
                        projectionMatrix,
                        colorCorrectionRgba, DEFAULT_COLOR
                )
            }
        } catch (t: Throwable) {
            // Avoid crashing the application due to unhandled exceptions.
            Log.e(
                    TAG,
                    "Exception on the OpenGL thread",
                    t
            )
        } finally {
            GLES20.glDepthMask(true)
        }
    }

    private fun configureSession() {
        val config = Config(session)
        config.augmentedFaceMode = AugmentedFaceMode.MESH3D
        session!!.configure(config)
    }

    private fun toggleRecording(view: View) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            if(!isRecording) {
                val recordingConfig: RecordingConfig = RecordingConfig(session).apply {
                    mp4DatasetFilePath = buildFilename().path
                }
                try {
                    session!!.startRecording(recordingConfig)
                    isRecording = true
                    Log.d(TAG, "Video start error ${session!!.recordingStatus}")

                    updateRecordingUi()
                } catch (e: Exception) {
                    Log.d(TAG, "Video start error", e)
                    Toast.makeText(this, "Failed to start recording", Toast.LENGTH_SHORT).show()
                }
            }else{
                try {
                    session!!.stopRecording()
                    isRecording = false
                    updateRecordingUi()
                } catch (e: Exception) {
                    Log.d(TAG, "Video stop error", e)
                    Toast.makeText(this, "Failed to stop recording", Toast.LENGTH_SHORT).show()
                }
            }
//            val recording: Boolean = videoRecorder.onToggleRecord()
//            if (recording) {
//                recordButton.setImageResource(R.drawable.ic_stop)
//            } else {
//                recordButton.setImageResource(R.drawable.ic_recording)
//                val videoPath: String = videoRecorder.getVideoPath().getAbsolutePath()
//                Toast.makeText(this, "Video saved: $videoPath", Toast.LENGTH_SHORT).show()
//                Log.d(
//                    TAG,
//                    "Video saved: $videoPath"
//                )
//
//                // Send  notification of updated content.
//                val values = ContentValues()
//                values.put(MediaStore.Video.Media.TITLE, "Sceneform Video")
//                values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
//                values.put(MediaStore.Video.Media.DATA, videoPath)
//                contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
//            }

        } else {
            Log.e(
                    TAG,
                    "Video recording requires the WRITE_EXTERNAL_STORAGE permission"
            )
            Toast.makeText(
                    this,
                    "Video recording requires the WRITE_EXTERNAL_STORAGE permission",
                    Toast.LENGTH_LONG
            )
                    .show()
            val intent = Intent()
            intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            intent.data = Uri.fromParts("package", packageName, null)
            startActivity(intent)

        }

    }

    private fun updateRecordingUi() = if (isRecording) {
        recordButton.setImageResource(R.drawable.ic_stop)
    } else {
        recordButton.setImageResource(R.drawable.ic_recording)
    }

    private fun buildFilename():File {
        val videoDirectory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                .toString() + "/Sceneform")
        val videoBaseName = "Sample"

        val videoPath = File(videoDirectory, videoBaseName + java.lang.Long.toHexString(System.currentTimeMillis()) + ".mp4")
        val dir: File? = videoPath.parentFile
        if (!dir!!.exists()) {
            dir.mkdirs()
        }
        return videoPath
    }
}