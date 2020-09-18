package com.kevng2.treear

import android.content.Intent
import android.graphics.Bitmap
import android.os.*
import androidx.appcompat.app.AppCompatActivity
import android.util.Log
import android.view.*
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.lifecycle.LiveData
import com.google.android.material.snackbar.Snackbar
import com.google.ar.core.Anchor
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.NodeParent
import com.google.ar.sceneform.rendering.ModelRenderable
import com.google.ar.sceneform.ux.ArFragment
import com.google.ar.sceneform.ux.TransformableNode
import com.kevng2.treear.api.PolyApi
import com.techyourchance.threadposter.BackgroundThreadPoster
import kotlinx.android.synthetic.main.activity_camera.*
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class CameraActivity : AppCompatActivity() {
    private var mArFragment: ArFragment? = null
    private var isInsertMode: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?)  {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)
        setSupportActionBar(camera_toolbar)
        mArFragment = fragment as ArFragment
        supportActionBar?.setDisplayShowTitleEnabled(false)
        setUpModel()
        setUpPlane()
        take_picture_button.setOnClickListener {
            takePhoto()
        }

        val polyLiveData : LiveData<String> = PolyFetcher().fetchModels()
        polyLiveData.observe(this, androidx.lifecycle.Observer { responseString ->
            Log.d(TAG, "Response received: $responseString")
        })
        Log.d("CameraActivity", "onCreate (line 57): ${BuildConfig.POLY_API_KEY}")
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.activity_camera, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        super.onOptionsItemSelected(item)
        when (item.itemId) {
            R.id.trash -> {
                if (isInsertMode) {
                    item.setIcon(R.drawable.ic_done)
                } else {
                    item.setIcon(R.drawable.ic_delete)
                }

                isInsertMode = !isInsertMode
            }
            R.id.help -> {
                Log.d("CameraActivity", "onOptionsItemSelected (line 68): ")
                AlertDialog.Builder(this@CameraActivity)
                    .setPositiveButton(android.R.string.ok) { dialog, which -> }
                    .setMessage(
                        "Welcome to TreeVisualizer\n\n" +
                                "To start, wave your camera around like the animation is indicating on " +
                                "the camera preview. Then, an indicator will pop up to plant some trees!\n\n" +
                                "Tap on any dotted area to plant a tree\n\n" +
                                "You can switch trees anytime by selecting the trees at the bottom\n\n" +
                                "Trash Icon: Delete mode. Tap on tree to delete\n\n" +
                                "Check mark Icon: Switch back to add mode\n\n" +
                                "Pinch to grow the tree\n\n" +
                                "Hold one finger on the tree and use another finger to rotate the tree\n\n"
                    )
                    .create()
                    .show()
            }
        }
        return true
    }

    private fun setUpPlane() {
        mArFragment?.setOnTapArPlaneListener { hitResult, plane, motionEvent ->
            if (isInsertMode) {
                val anchor: Anchor = hitResult.createAnchor()
                val anchorNode = AnchorNode(anchor)
                anchorNode.setParent(mArFragment?.arSceneView?.scene as NodeParent)
                createModel(anchorNode)
            }
        }
    }

    private fun createModel(anchorNode: AnchorNode) {
        val node = TransformableNode(mArFragment?.transformationSystem)
        node.setParent(anchorNode)
        node.renderable = mModelRenderable
        node.setOnTapListener { hitTestResult, motionEvent ->
            if (!isInsertMode) {
                mArFragment?.arSceneView?.scene?.removeChild(anchorNode)
                anchorNode.anchor?.detach()
                anchorNode.setParent(null)
                anchorNode.renderable = null
            }
        }
        node.select()
    }

    private fun setUpModel() {
        ModelRenderable.builder()
            .setSource(applicationContext, mModelId)
            .build()
            .thenAccept { renderable: ModelRenderable -> mModelRenderable = renderable }
            .exceptionally {
                Toast.makeText(
                    applicationContext, "Model can't be loaded",
                    Toast.LENGTH_SHORT
                ).show(); return@exceptionally null
            }
    }

    private fun generateFilename(): String {
        val date: String = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault()).format(
            Date()
        )
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            .toString() + File.separator + "Sceneform/" + date + "_screenshot.jpg"
    }

    private fun saveBitmapToDisk(bitmap: Bitmap, filename: String) {
        val out = File(filename)
        if (!out.parentFile?.exists()!!) {
            out.parentFile?.mkdirs()
        }
        val outputStream = FileOutputStream(filename)
        val outputData = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputData)
        outputData.writeTo(outputStream)
        outputStream.flush()
        outputStream.close()
    }

    private fun takePhoto() {
        val filename = generateFilename()
        val frag = fragment as ArFragment
        val view = frag.arSceneView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val handlerThread = HandlerThread("PixelCopier")
        handlerThread.start()

        PixelCopy.request(view, bitmap, { copyResult ->
            if (copyResult == PixelCopy.SUCCESS) {
                saveBitmapToDisk(bitmap, filename)
                val snackbar = Snackbar.make(
                    findViewById(android.R.id.content),
                    "Photo saved",
                    Snackbar.LENGTH_LONG
                )
                snackbar.setAction("Open in Photos") { v ->
                    val photoFile = File(filename)
                    val photoUri = FileProvider.getUriForFile(
                        this@CameraActivity,
                        "$packageName.ar.codelab.name.provider", photoFile
                    )
                    intent = Intent(Intent.ACTION_VIEW, photoUri)
                    intent.setDataAndType(photoUri, "image/*")
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(intent)
                }
                snackbar.show()
            }
            handlerThread.quitSafely()
        }, Handler(handlerThread.looper))
    }

    companion object {
        var mModelId: Int = R.raw.oak_tree
        var mModelRenderable: ModelRenderable? = null
        private const val TAG = "CameraActivity"
    }
}
