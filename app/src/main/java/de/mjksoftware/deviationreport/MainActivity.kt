package de.mjksoftware.deviationreport
import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Size
import android.view.KeyEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.squareup.picasso.Picasso
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


/***************************************************************************************************
 *
 *   MAIN CLASS WITH DECLARATIONS
 *
 ***************************************************************************************************/
@SuppressLint("SetTextI18n")    // don't annoy with string warnings
class MainActivity : AppCompatActivity() {
    // now I know how to CONST in kotlin :-)
    companion object {
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val DATE_FORMAT = "yyyy-MM-dd"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE
        )
        private const val VIDEO_CAPTURE = 101

        const val DAMAGE_TYPE_HM = 0
        const val DAMAGE_TYPE_L = 1
        const val DAMAGE_TYPE_LQ = 2
        const val DAMAGE_TYPE_UL = 3
        const val DAMAGE_TYPE_ULL = 4
        const val DAMAGE_TYPE_DOCUMENT = 5
    }

    private var damageHM = DamageType2("HM", arrayOf(
            "PP Feed/LL damaged or missing",
            "PP Feed/LL position issue",
            "Fork entry height insufficient",
            "HM missing or deformed"
    ))

    private var damageL = DamageType2("L", arrayOf(
            "defect on other labels",
            "Top filling label issue"
    ))

    private var damageLQ = DamageType2("LQ", arrayOf(
            "upper UL overhanging",
            "upper UL sinked",
            "UL shifted/UL position issue",
            "ULL not visible",
            "filling solution",
            "loading sequence"
    ))

    private var damageUL = DamageType2("UL", arrayOf(
            "UL fixation",
            "open packages",
            "not neat and clean",
            "general protection",
            "DWP deviation",
            "Packaging construction"
    ))

    private var damageULL = DamageType2("ULL", arrayOf(
            "Barcode not readable",
            "ULL wrong placement",
            "ULL wrong information",
            "ULL missing/bad fixation",
            "both ULLs not matching"
    ))

    private var damageDocument = DamageType2("Doc.", arrayOf("Doc."))

    private var damage = arrayOf(damageHM, damageL, damageLQ, damageUL, damageULL, damageDocument)

    private var currentDamage: Int = DAMAGE_TYPE_LQ
    private var lastDamage: Int = DAMAGE_TYPE_L
    private var currentTruckNumber: String = ""
    private var firstPicture: Boolean = true
    private var countInDocumentation = true

    private lateinit var photoFile: File
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var camera: Camera
    private lateinit var cameraControl: CameraControl
    private var flash: Int = ImageCapture.FLASH_MODE_OFF
    private var torch: Boolean = false
    private var linearZoom: Float = 0f


    /***********************************************************************************************
     *
     *   INITIALIZE VIEW, CHECK FOR PERMISSIONS, START CAMERA, SET UP LISTENERS
     *
     ***********************************************************************************************/
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (supportActionBar != null) supportActionBar?.hide()


        // Request camera permissions
        if (!allPermissionsGranted()) ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        startCamera()

        // Set up listeners
        imgFlashSwitch.setOnClickListener { switchFlash() }
        imgTorchSwitch.setOnClickListener { switchTorch() }
        btnDialogNewTruckCancel.setOnClickListener { frmDialogNewTruck.isVisible = false }
        btnNewTruck.setOnClickListener { showNewTruckDialog() }

        btnLQ.setOnClickListener { cycleDamage(DAMAGE_TYPE_LQ) }
        btnHM.setOnClickListener { cycleDamage(DAMAGE_TYPE_HM) }
        btnUL.setOnClickListener { cycleDamage(DAMAGE_TYPE_UL) }
        btnULL.setOnClickListener { cycleDamage(DAMAGE_TYPE_ULL) }
        btnL.setOnClickListener { cycleDamage(DAMAGE_TYPE_L) }
        btnDocument.setOnClickListener { cycleDamage(DAMAGE_TYPE_DOCUMENT) }

        btnSave.setOnClickListener {
            if (currentTruckNumber != "") {
                countInDocumentation = true
                takePhoto()
            } else showNewTruckDialog()
        }

        btnSaveDontCount.setOnClickListener {
            if (currentTruckNumber != "") {
                countInDocumentation = false
                takePhoto()
            } else showNewTruckDialog()
        }

        btnLastPicture.setOnClickListener {
            if (!firstPicture) {
                frmDeleteOldPicture.isVisible = true
                Picasso.get().load(photoFile).into(imgLastPicture)
            }
        }

        btnDeleteLastPicture.setOnClickListener {
            if (photoFile.exists()) photoFile.delete()
            firstPicture = true
            frmDeleteOldPicture.isVisible = false
        }

        btnDeleteLastPictureCancel.setOnClickListener {
            frmDeleteOldPicture.isVisible = false
        }

        /******************************************************************************************
         * OnClickListener and TextChangedListener for New Truck Dialog
         * both work, but on some systems clicking "okay" when edit is done
         * will directly save and close the dialog -> more user friendly
         *******************************************************************************************/
        // EditText OnClickListener...
        btnDialogNewTruckSave.setOnClickListener { hideNewTruckDialog() }
        // ...EditText OnKeyListener...
        editDialogNewTruck.setOnKeyListener { _, keyCode, event ->
            when {
                //Check if it is the Enter-Key,      Check if the Enter Key was pressed down
                ((keyCode == KeyEvent.KEYCODE_ENTER) && (event.action == KeyEvent.ACTION_DOWN)) -> {
                    hideNewTruckDialog()
                    return@setOnKeyListener true
                } else -> false
            }
        }
        // ...and last but not least: EditText OnFocusChangeListener
        editDialogNewTruck.setOnFocusChangeListener { _, hasFocus ->  if (!hasFocus) hideNewTruckDialog() }
        /******************************************************************************************/


        imgVideoCapture.setOnClickListener {
            if (currentTruckNumber != "") {
                val intent = Intent(MediaStore.ACTION_VIDEO_CAPTURE)
                startActivityForResult(intent, VIDEO_CAPTURE)
            } else showNewTruckDialog()
        }

        currentDamage = DAMAGE_TYPE_DOCUMENT
        // initialize this at the end of onCreate() !! <- must be here. nowhere else
        cameraExecutor = Executors.newSingleThreadExecutor()
    }


    /***********************************************************************************************
     *
     *   URI? something about MediaCenter. Don't know yet. Have to read. But needed a REAL path NOW!
     *
     ***********************************************************************************************/
    private fun getRealPathFromURI(context: Context, uri: Uri): String {
        var realPath = String()
        uri.path?.let { path ->

            val databaseUri: Uri
            val selection: String?
            val selectionArgs: Array<String>?
            if (path.contains("/document/image:")) { // files selected from "Documents"
                databaseUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                selection = "_id=?"
                selectionArgs = arrayOf(DocumentsContract.getDocumentId(uri).split(":")[1])
            } else { // files selected from all other sources, especially on Samsung devices
                databaseUri = uri
                selection = null
                selectionArgs = null
            }
            try {
                val column = "_data"
                val projection = arrayOf(column)
                val cursor = context.contentResolver.query(
                        databaseUri,
                        projection,
                        selection,
                        selectionArgs,
                        null
                )
                cursor?.let {
                    if (it.moveToFirst()) {
                        val columnIndex = cursor.getColumnIndexOrThrow(column)
                        realPath = cursor.getString(columnIndex)
                    }
                    cursor.close()
                }
            } catch (e: Exception) {
                println(e)
            }
        }
        return realPath
    }


    /***********************************************************************************************
     *
     *   VIDEO CAPTURE INTENT RESULT
     *
     ***********************************************************************************************/
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (data != null) {
            val videoUri = data.data

            if (requestCode == VIDEO_CAPTURE) {
                if (resultCode == Activity.RESULT_OK) {
                    if (videoUri != null) {
                        val videoFileSource = File(getRealPathFromURI(this, videoUri))
                        videoFileSource.copyTo(
                                File(
                                        getOutputDirectory(),
                                        "${damage[currentDamage].name}${damage[currentDamage].current}_" + SimpleDateFormat(
                                                FILENAME_FORMAT, Locale.US
                                        ).format(System.currentTimeMillis()) + ".mp4"
                                )
                        )
                        videoFileSource.delete()
                        Toast.makeText(this, "video saved", Toast.LENGTH_LONG).show()
                    }
                } else if (resultCode == Activity.RESULT_CANCELED) {
                    Toast.makeText(
                            this, "Video recording cancelled.",
                            Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                            this, "Failed to record video",
                            Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }


    /***********************************************************************************************
     *
     *   SAVE / LOAD STATE
     *
     ***********************************************************************************************/
    private fun saveState() {
        val sharedPref = this.getPreferences(Context.MODE_PRIVATE) ?: return

        with (sharedPref.edit()) {
            for (i in damage.indices)
                for(j in damage[i].counter.indices) putInt("dmg_${damage[i].name}_$j", damage[i].counter[j])
            putInt("currentDamage", currentDamage)
            putString("truck", currentTruckNumber)

            apply()
        }
    }

    override fun onPause() {
        super.onPause()
        saveState()
    }

    override fun onStop() {
        super.onStop()
        saveState()
    }

    override fun onResume() {
        super.onResume()

        val sharedPref = this.getPreferences(Context.MODE_PRIVATE) ?: return

        with (sharedPref) {
            for (i in damage.indices)
                for (j in damage[i].counter.indices) damage[i].counter[j] = getInt("dmg_${damage[i].name}_$j", 0)
            currentDamage = getInt("currentDamage", DAMAGE_TYPE_DOCUMENT)
            currentTruckNumber = getString("truck", "").toString()
        }
        txtTruckInfo.text = resources.getString(R.string.lblTruckInfo) + currentTruckNumber
        cycleDamage(DAMAGE_TYPE_DOCUMENT)
        startCamera()
    }


    /***********************************************************************************************
     *
     *   USE VOLUME KEYS FOR CAMERA ZOOM
     *
     ***********************************************************************************************/
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (linearZoom <= 0.9) {
                    linearZoom += 0.1f
                }
                cameraControl.setLinearZoom(linearZoom)
                true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (linearZoom >= 0.1) {
                    linearZoom -= 0.1f
                }
                cameraControl.setLinearZoom(linearZoom)
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }


    /***********************************************************************************************
     *
     *   START CAMERA AND INITIALIZE PREVIEW
     *
     ***********************************************************************************************/
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        val orientation = resources.configuration.orientation

        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of camera to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            var size = Size(640, 480)

            if (orientation == Configuration.ORIENTATION_PORTRAIT) size = Size(480, 640)

            val preview = Preview.Builder()
                    .setTargetResolution(size)
                    .build()
                    .also { it.setSurfaceProvider(viewFinder.surfaceProvider) }

            imageCapture = ImageCapture.Builder()
                    .setFlashMode(flash)
                    .setTargetResolution(size)
                    .build()

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()
                // Bind use cases to camera
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
                cameraControl = camera.cameraControl
            } catch (exc: Exception) {
            }
        }, ContextCompat.getMainExecutor(this))
    }


    /***********************************************************************************************
     *
     *   FIND THE D****D OUTPUT DIRECTORY ON SD CARD. That was hard, hope it works everywhere.
     *
     ***********************************************************************************************/
    private fun getOutputDirectory(): File {
        val externalStorageVolumes: Array<out File> =
                ContextCompat.getExternalFilesDirs(applicationContext, null)
        val secondaryExternalStorage: File =
                if (externalStorageVolumes.size > 1) externalStorageVolumes[1]
                else externalStorageVolumes[0]

        return secondaryExternalStorage.let {
            File(it, "/$currentTruckNumber/").apply { mkdirs() } }
    }


    /***********************************************************************************************
     *
     *   TAKE PHOTO AND SAVE TEXT FILE
     *
     ***********************************************************************************************/
    private fun takePhoto() {
        // Get a stable reference of the modifiable image capture use case
        val imageCapture = imageCapture ?: return
        // Create time-stamped output file to hold the image
        photoFile = File(
                getOutputDirectory(),
                "${damage[currentDamage].getCurrentString()}_" + SimpleDateFormat(
                        FILENAME_FORMAT, Locale.US
                ).format(System.currentTimeMillis()) + ".jpg"
        )

        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        // Set up image capture listener, which is triggered after photo has
        // been taken
        imageCapture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onError(exc: ImageCaptureException) {
                        Toast.makeText(applicationContext,
                                "${getString(R.string.hintPhotoNotSaved)} ->\n${exc.message}", Toast.LENGTH_LONG).show()
                    }

                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        if (countInDocumentation) damage[currentDamage].add()
                        if (firstPicture) firstPicture = false

                        // save damage counters to text file
                        val analysisFile = File(
                                getOutputDirectory(),
                                "${currentTruckNumber}.txt"
                        )

                        analysisFile.apply {
                            if (exists()) delete()
                            createNewFile()
                            appendText("truck: ${currentTruckNumber}\n")
                            appendText(" date: ${SimpleDateFormat(DATE_FORMAT, Locale.US).format(System.currentTimeMillis())}\n\n")
                            appendText("______________________\n")
                            appendText("complaints:\tnumber:\n\n")
                        }
                        for (i in 0..damage.lastIndex) damage[i].saveToFile(analysisFile)
                        setCountingLabels()
                        // leave a message
                        Toast.makeText(applicationContext, resources.getString(R.string.hintPhotoSaved), Toast.LENGTH_SHORT).show()
                    }
                })
    }


    /***********************************************************************************************
     *
     *   PERMISSION CONTROL
     *
     ***********************************************************************************************/
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
            requestCode: Int, permissions: Array<String>, grantResults:
            IntArray
    ) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(
                        this,
                        "Permissions not granted by the user.",
                        Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }


    /***********************************************************************************************
     *
     *   CLEAN UP
     *
     ***********************************************************************************************/
    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        // should be more done here? It's so empty...
    }


    /***********************************************************************************************
     *
     *   BUTTON APPEARANCE
     *
     ***********************************************************************************************/
    private fun allButtonsShow() {
        btnLQ.isVisible = true
        btnHM.isVisible = true
        btnUL.isVisible = true
        btnULL.isVisible = true
        btnDocument.isVisible = true
        btnL.isVisible = true
        btnSave.isVisible = true
        startCamera()
    }

    private fun allButtonsHide() {
        btnLQ.isVisible = false
        btnHM.isVisible = false
        btnUL.isVisible = false
        btnULL.isVisible = false
        btnL.isVisible = false
        btnDocument.isVisible = false
        btnSave.isVisible = false
        startCamera()
    }

    private fun allButtonsTranslucent() {
        btnLQ.alpha = .4f
        btnHM.alpha = .4f
        btnUL.alpha = .4f
        btnULL.alpha = .4f
        btnL.alpha = .4f
        btnDocument.alpha = .4f
    }


    /***********************************************************************************************
     *
     *   PLAYING WITH THE CAMERA LIGHT
     *
     ***********************************************************************************************/
    private fun switchFlash() {
        when (flash) {
            ImageCapture.FLASH_MODE_OFF -> {
                flash = ImageCapture.FLASH_MODE_ON
                imgFlashSwitch.setImageResource(R.drawable.icoflash)
            }
            ImageCapture.FLASH_MODE_ON -> {
                flash = ImageCapture.FLASH_MODE_OFF
                imgFlashSwitch.setImageResource(R.drawable.icoflashoff)
            }
        }
        startCamera()
    }

    private fun switchTorch() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            torch = !torch
            if (torch) {
                imgTorchSwitch.setImageResource(R.drawable.icotorchon)
                cameraControl.enableTorch(true)
            }
            else {
                imgTorchSwitch.setImageResource(R.drawable.icotorchoff)
                cameraControl.enableTorch(false)
            }
        }
        else
        {
            Toast.makeText(this, getString(R.string.hintTorchNotAvailable), Toast.LENGTH_SHORT).show()
        }
    }


    /***********************************************************************************************
     *
     *   CYCLE THROUGH THE DAMAGE TYPES OF ONE CATEGORY
     *
     ***********************************************************************************************/
    private fun cycleDamage(type: Int) {
        // set damage type from button
        currentDamage = type
        damage[currentDamage].cycle()
        if (currentDamage != lastDamage) {
            damage[currentDamage].current = 0
            lastDamage = currentDamage
        }
        setCountingLabels()
    }


    /***********************************************************************************************
     *
     *   MANAGE BUTTON AND TEXT LABELS
     *
     ***********************************************************************************************/
    private fun setCountingLabels() {
        // first name al buttons without number and all labels with total amounts
        btnLQ.text = damage[DAMAGE_TYPE_LQ].name
        btnHM.text = damage[DAMAGE_TYPE_HM].name
        btnUL.text = damage[DAMAGE_TYPE_UL].name
        btnULL.text = damage[DAMAGE_TYPE_ULL].name
        btnL.text = damage[DAMAGE_TYPE_L].name
        btnDocument.text = damage[DAMAGE_TYPE_DOCUMENT].name
        txtNumLQ.text = damage[DAMAGE_TYPE_LQ].getTotalCount().toString()
        txtNumHM.text = damage[DAMAGE_TYPE_HM].getTotalCount().toString()
        txtNumUL.text = damage[DAMAGE_TYPE_UL].getTotalCount().toString()
        txtNumULL.text = damage[DAMAGE_TYPE_ULL].getTotalCount().toString()
        txtNumL.text = damage[DAMAGE_TYPE_L].getTotalCount().toString()
        txtNumDocs.text = damage[DAMAGE_TYPE_DOCUMENT].getTotalCount().toString()

        allButtonsTranslucent()
        // then set explicit button and count labels captions for respectively button
        with(damage[currentDamage]) {
            when (currentDamage) {
                DAMAGE_TYPE_L -> {
                    txtNumL.text = "${getTotalCount()} ,${counter[current]}"
                    btnL.text = getCurrentString(); btnL.alpha = 1f
                }
                DAMAGE_TYPE_LQ -> { txtNumLQ.text = "${getTotalCount()} ,${counter[current]}"
                    btnLQ.text = getCurrentString(); btnLQ.alpha = 1f }
                DAMAGE_TYPE_HM -> { txtNumHM.text = "${getTotalCount()} ,${counter[current]}"
                    btnHM.text = getCurrentString(); btnHM.alpha = 1f }
                DAMAGE_TYPE_UL -> { txtNumUL.text = "${getTotalCount()} ,${counter[current]}"
                    btnUL.text = getCurrentString(); btnUL.alpha = 1f }
                DAMAGE_TYPE_ULL -> { txtNumULL.text = "${getTotalCount()} ,${counter[current]}"
                    btnULL.text = getCurrentString(); btnULL.alpha = 1f }
                DAMAGE_TYPE_DOCUMENT -> btnDocument.alpha = 1f
            }
            txtDamageType.text = getDescription()
        }
    }


    /***********************************************************************************************
     *
     *   NEW TRUCK DIALOG
     *
     ***********************************************************************************************/
    private fun showNewTruckDialog() {
        allButtonsHide()
        frmDialogNewTruck.isVisible = true
    }

    private fun hideNewTruckDialog() {
        if (editDialogNewTruck.text.toString() != "") {
            currentTruckNumber = editDialogNewTruck.text.toString()
            // reset damage counter
            for (i in damage.indices) damage[i].resetCounter()
            setCountingLabels()
            firstPicture = true
            frmDialogNewTruck.isVisible = false
            txtTruckInfo.text = resources.getString(R.string.lblTruckInfo) + currentTruckNumber
            allButtonsShow()
        } else
            Toast.makeText(applicationContext, "please enter a truck number first", Toast.LENGTH_LONG).show()
    }
}

