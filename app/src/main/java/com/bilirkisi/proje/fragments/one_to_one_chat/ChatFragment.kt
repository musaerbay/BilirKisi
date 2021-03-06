package com.bilirkisi.proje.fragments.one_to_one_chat

import android.Manifest
import android.animation.AnimatorInflater
import android.animation.AnimatorSet
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.Timestamp
import com.google.gson.Gson
import com.karumi.dexter.Dexter
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionDeniedResponse
import com.karumi.dexter.listener.PermissionGrantedResponse
import com.karumi.dexter.listener.single.PermissionListener
import com.bilirkisi.proje.R
import com.bilirkisi.proje.databinding.ChatFragmentBinding
import com.bilirkisi.proje.models.*
import com.bilirkisi.proje.util.AuthUtil
import com.bilirkisi.proje.util.CLICKED_USER
import com.bilirkisi.proje.util.LOGGED_USER
import com.bilirkisi.proje.util.eventbus_events.PermissionEvent
import com.bilirkisi.proje.util.eventbus_events.UpdateRecycleItemEvent
import com.stfalcon.imageviewer.StfalconImageViewer
import com.stfalcon.imageviewer.loader.ImageLoader
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.attachment_layout.view.*
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.io.IOException
import java.util.*


const val SELECT_CHAT_IMAGE_REQUEST = 3
const val CHOOSE_FILE_REQUEST = 4


class ChatFragment : Fragment() {

    private var recordStart = 0L
    private var recordDuration = 0L

    private lateinit var mBottomSheetBehavior: BottomSheetBehavior<NestedScrollView>
    private var recorder: MediaRecorder? = null
    var isRecording = false //??imdi kay??t olsun ya da olmas??n
    var isRecord = true //metin mesaj?? m?? yoksa kay??t m??
    private lateinit var loggedUser: User
    private lateinit var clickedUser: User


    private var messageList = mutableListOf<Message>()
    lateinit var binding: ChatFragmentBinding
    private val adapter: ChatAdapter by lazy {
        ChatAdapter(context, object : MessageClickListener {
            override fun onMessageClick(position: Int, message: Message) {
          //t??klanan ????e tam ekranda g??r??nt?? a????ksa, yak??nla??t??rmak i??in k??st??rma
                if (message.type == 1.0) {

                    binding.fullSizeImageView.visibility = View.VISIBLE

                    StfalconImageViewer.Builder<MyImage>(
                        activity!!,
                        listOf(MyImage((message as ImageMessage).uri!!)),
                        ImageLoader<MyImage> { imageView, myImage ->
                            Glide.with(activity!!)
                                .load(myImage.url)
                                .apply(RequestOptions().error(R.drawable.ic_broken_image_black_24dp))
                                .into(imageView)
                        })
                        .withDismissListener { binding.fullSizeImageView.visibility = View.GONE }
                        .show()


                }
                // kullan??c??n??n dosyay?? indirmek istedi??ini onaylayan ileti??im kutusunu g??ster ve ard??ndan indirmeye veya iptal etmeye devam et
                else if (message.type == 2.0) {
                    // indirmemiz gereken dosya mesaj??
                    val dialogBuilder = context?.let { it1 -> AlertDialog.Builder(it1) }
                    dialogBuilder?.setMessage("T??klanan dosyay?? indirmek istiyor musunuz?")
                        ?.setPositiveButton(
                            "yes"
                        ) { _, _ ->
                            downloadFile(message)
                        }?.setNegativeButton("cancel", null)?.show()

                } else if (message.type == 3.0) {
                    adapter.notifyDataSetChanged()
                }
            }

        })
    }

    private fun downloadFile(message: Message) {
        //depolama iznini kontrol edin ve verilmi?? olan?? indirin
        Dexter.withActivity(activity!!)
            .withPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            .withListener(object : PermissionListener {
                override fun onPermissionGranted(response: PermissionGrantedResponse?) {
                    //download file
                    val downloadManager =
                        activity!!.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    val uri = Uri.parse((message as FileMessage).uri)
                    val request = DownloadManager.Request(uri)
                    request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    request.setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        uri.lastPathSegment
                    )
                    downloadManager.enqueue(request)
                }
                // Dosyalar i??in ??zin Gerek??esi G??sterilmelidir.
                override fun onPermissionRationaleShouldBeShown(
                    permission: com.karumi.dexter.listener.PermissionRequest?,
                    token: PermissionToken?
                ) {
                    token?.continuePermissionRequest()
                    //notify parent activity that permission denied to show toast for manual permission giving
                    showSnackBar()
                }

                override fun onPermissionDenied(response: PermissionDeniedResponse?) {
                    //notify parent activity that permission denied to show toast for manual permission giving
                    EventBus.getDefault().post(PermissionEvent())
                }
            }).check()
    }


    companion object {
        fun newInstance() = ChatFragment()
    }

    private lateinit var viewModel: ChatViewModel
    private lateinit var viewModeldFactory: ChatViewModelFactory

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        setHasOptionsMenu(true)

        binding = DataBindingUtil.inflate(inflater, R.layout.chat_fragment, container, false)
        return binding.root
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        getActivity()?.navView?.visibility = View.GONE
        //alt sayfay?? ayarla
        mBottomSheetBehavior = BottomSheetBehavior.from(binding.bottomSheet)

        // kay??t g??r??n??m??n?? ayarla
        handleRecord()


        // kay??tl?? kullan??c??y?? payla????lan tercihlerden al??n
        val mPrefs: SharedPreferences = activity!!.getPreferences(Context.MODE_PRIVATE)
        val gson = Gson()
        val json: String? = mPrefs.getString(LOGGED_USER, null)
        loggedUser = gson.fromJson(json, User::class.java)

        //Ki??i b??l??m??nden al??c?? verilerini al(NOTE:kullan??c?? bilgileri FCM-NOTIFICATION den ??ekiliyorsa yanl??zca:  id,username)
        clickedUser = gson.fromJson(arguments?.getString(CLICKED_USER), User::class.java)


        activity?.title = " ${clickedUser.username} ile sohbet"


        if (clickedUser.uid != null) {
            viewModeldFactory = ChatViewModelFactory(loggedUser.uid, clickedUser.uid.toString())
            viewModel =
                ViewModelProviders.of(this, viewModeldFactory).get(ChatViewModel::class.java)
        }


        // Yaz??l??m klavyesi g??sterildi??inde d??zenleri yukar?? ta????
        activity!!.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)


        // klavyede mesaj g??nder tamam t??klay??n
        binding.messageEditText.setOnEditorActionListener { _, _, _ ->
            sendMessage()
            true
        }


        binding.recycler.adapter = adapter

        // geri d??n????t??r??c??n??n g??stermesi i??in mesaj listesi ilet
        viewModel.loadMessages().observe(viewLifecycleOwner, Observer { mMessagesList ->
            messageList = mMessagesList as MutableList<Message>
            ChatAdapter.messageList = messageList
            adapter.submitList(mMessagesList)
            // geri d??n??????m cihaz??ndaki son ????elere kayd??r (son mesajlar)
            binding.recycler.scrollToPosition(mMessagesList.size - 1)

        })


        // alt sayfadaki ????elerin t??klamas??n?? i??le
        binding.bottomSheet.sendPictureButton.setOnClickListener {
            selectFromGallery()
            mBottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        }

        binding.bottomSheet.sendFileButton.setOnClickListener {
            openFileChooser()
            mBottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        }

        binding.bottomSheet.hide.setOnClickListener {
            mBottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        }


        // alt sayfay?? g??ster
        binding.attachmentImageView.setOnClickListener {
            mBottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
        }


        // yeni kay??t y??klendi??inde g??zlemle
        viewModel.chatRecordDownloadUriMutableLiveData.observe(
            viewLifecycleOwner,
            Observer { recordUri ->
                println("observer called")
                viewModel.sendMessage(
                    RecordMessage(
                        AuthUtil.getAuthId(),
                        Timestamp(Date()),
                        3.0,
                        clickedUser.uid,
                        loggedUser.username,
                        recordDuration.toString(),
                        recordUri.toString(),
                        null,
                        null
                    )
                )
            })
    }


    private fun handleRecord() {


        // metin mesaj??na ba??l?? olarak fab simgesini de??i??tirin empty_box veya de??il
        binding.messageEditText.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (s.isNullOrEmpty()) {
                    // empty_box metin mesaj??
                    binding.recordFab.setImageResource(R.drawable.mic)
                    isRecord = true
                } else {
                    binding.recordFab.setImageResource(R.drawable.sendicon)
                    isRecord = false
                }
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            }

        })


        binding.recordFab.setOnClickListener {
            if (isRecord) {
                // mesaj?? kaydet
                if (isRecording) {
                    // boyutu ve rengi veya d????meyi de??i??tirin, b??ylece kullan??c?? kay??t i??leminin bitti??ini bilir
                    val regainer = AnimatorInflater.loadAnimator(
                        context,
                        R.animator.regain_size
                    ) as AnimatorSet
                    regainer.setTarget(binding.recordFab)
                    regainer.start()
                    binding.recordFab.backgroundTintList =
                        ColorStateList.valueOf(Color.parseColor("#b39ddb"))
                    // kayd?? durdur ve kayd?? y??kle
                    stopRecording()
                    showPlaceholderRecord()
                    viewModel.uploadRecord("${activity!!.externalCacheDir?.absolutePath}/audiorecord.3gp")
                    Toast.makeText(context, "Finished recording", Toast.LENGTH_SHORT).show()
                    isRecording = !isRecording

                } else {

                    Dexter.withActivity(activity)
                        .withPermission(Manifest.permission.RECORD_AUDIO)
                        .withListener(object : PermissionListener {
                            override fun onPermissionGranted(response: PermissionGrantedResponse?) {
                                // d????menin boyutunu ve rengini de??i??tirin , b??ylece kullan??c?? kayd??n?? bilecek
                                val increaser = AnimatorInflater.loadAnimator(
                                    context,
                                    R.animator.increase_size
                                ) as AnimatorSet
                                increaser.setTarget(binding.recordFab)
                                increaser.start()
                                binding.recordFab.backgroundTintList =
                                    ColorStateList.valueOf(Color.parseColor("#EE4B4B"))
                                // kayda ba??la
                                startRecording()
                                Toast.makeText(context, "Recording", Toast.LENGTH_SHORT).show()
                                isRecording = !isRecording
                            }

                            override fun onPermissionRationaleShouldBeShown(
                                permission: com.karumi.dexter.listener.PermissionRequest?,
                                token: PermissionToken?
                            ) {
                                token?.continuePermissionRequest()
                                //Bu aktiviteye izin verilmedi??ini g??ster , manuel izin vermek i??in
                                showSnackBar()
                            }

                            override fun onPermissionDenied(response: PermissionDeniedResponse?) {
                                //Bu aktiviteye izin verilmedi??ini g??ster , manuel izin vermek i??in
                                showSnackBar()
                            }
                        }).check()

                }

            } else {
                //metin mesaj??
                sendMessage()
            }
        }


    }


    private fun sendMessage() {
        if (binding.messageEditText.text.isEmpty()) {
            Toast.makeText(context, getString(R.string.empty_message), Toast.LENGTH_LONG).show()
            return
        }
        viewModel.sendMessage(
            TextMessage(
                loggedUser.uid,
                Timestamp(Date()),
                0.0,
                clickedUser.uid,
                loggedUser.username,
                binding.messageEditText.text.toString()
            )
        )

        binding.messageEditText.setText("")
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        // dosya sonucunu se??in
        if (requestCode == CHOOSE_FILE_REQUEST && data != null && resultCode == AppCompatActivity.RESULT_OK) {

            val filePath = data.data

            showPlaceholderFile(filePath)

            // sohbet dosyas?? y??klendi.  uri'yi mesajla birlikte saklay??n
            viewModel.uploadChatFileByUri(filePath).observe(this, Observer { chatFileMap ->
                viewModel.sendMessage(
                    FileMessage(
                        loggedUser.uid,
                        Timestamp(Date()),
                        2.0,
                        clickedUser.uid,
                        loggedUser.username,
                        chatFileMap["fileName"].toString(),
                        chatFileMap["downloadUri"].toString()
                    )
                )

            })

        }

        // resim sonucunu se??
        if (requestCode == SELECT_CHAT_IMAGE_REQUEST && data != null && resultCode == AppCompatActivity.RESULT_OK) {

            // resim y??klenene kadar geri d??n????t??r??cudaki resimli sahte ????eyi g??ster
            showPlaceholderPhoto(data.data)

            // resmi firebase deposuna y??kle
            viewModel.uploadChatImageByUri(data.data)
                .observe(this, Observer { uploadedChatImageUri ->
                    // sohbet resmi y??klendi ??imdi uri'yi mesajla birlikte saklay??n
                    viewModel.sendMessage(
                        ImageMessage(
                            loggedUser.uid,
                            Timestamp(Date()),
                            1.0,
                            clickedUser.uid,
                            loggedUser.username,
                            uploadedChatImageUri.toString()
                        )
                    )
                })

        }


    }


    private fun openFileChooser() {
        val i = Intent(Intent.ACTION_GET_CONTENT)
        i.type = "*/*"
        try {
            startActivityForResult(i, CHOOSE_FILE_REQUEST)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(
                context,
                "Bu cihazda uygun bir dosya y??neticisi bulunamad??",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showPlaceholderPhoto(data: Uri?) {
        messageList.add(
            ImageMessage(
                AuthUtil.getAuthId(),
                null,
                1.0,
                clickedUser.uid,
                loggedUser.username,
                data.toString()
            )
        )
        adapter.submitList(messageList)
        adapter.notifyItemInserted(messageList.size - 1)
        binding.recycler.scrollToPosition(messageList.size - 1)
    }


    private fun showPlaceholderRecord() {
        //y??klemeleri kaydederken ilerleme ??ubu??uyla g??ster
        messageList.add(
            RecordMessage(
                AuthUtil.getAuthId(),
                null,
                8.0,
                null,
                null,
                null,
                null,
                null,
                null
            )
        )
        adapter.submitList(messageList)
        adapter.notifyItemInserted(messageList.size - 1)
        binding.recycler.scrollToPosition(messageList.size - 1)
    }


    private fun showPlaceholderFile(data: Uri?) {
        messageList.add(
            FileMessage(
                AuthUtil.getAuthId(),
                null,
                2.0,
                clickedUser.uid,
                loggedUser.username,
                data.toString(),
                data?.path.toString()
            )
        )
        adapter.submitList(messageList)
        adapter.notifyItemInserted(messageList.size - 1)
        binding.recycler.scrollToPosition(messageList.size - 1)
    }

    private fun selectFromGallery() {
        var intent = Intent()
        intent.type = "image/*"
        intent.action = Intent.ACTION_GET_CONTENT
        startActivityForResult(
            Intent.createChooser(intent, "Resim se??"),
            SELECT_CHAT_IMAGE_REQUEST
        )
    }

    private fun showSnackBar() {
        Snackbar.make(
            binding.coordinator,
            "Bu ??zelli??in ??al????mas?? i??in izin gerekiyor",
            Snackbar.LENGTH_LONG
        ).setAction(
            "Grant", View.OnClickListener {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                val uri = Uri.fromParts("package", activity!!.packageName, null)
                intent.data = uri
                startActivity(intent)
            }
        ).show()

    }


    private fun startRecording() {

        // kayd??n saklanaca???? dosyan??n ad??
        val fileName = "${activity!!.externalCacheDir?.absolutePath}/audiorecord.3gp"

        recorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
            setOutputFile(fileName)
            setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            try {
                prepare()

            } catch (e: IOException) {
                println("ChatFragment.startRecording${e.message}")
            }

            start()
            recordStart = Date().time
        }
    }

    private fun stopRecording() {

        recorder?.apply {
            stop()
            release()
            recorder = null
        }

        recordDuration = Date().time - recordStart

    }

    override fun onStart() {
        super.onStart()
        EventBus.getDefault().register(this)
    }

    override fun onStop() {
        super.onStop()
        EventBus.getDefault().unregister(this)
    }


    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onRecycleItemEvent(event: UpdateRecycleItemEvent) {
        adapter.notifyItemChanged(event.adapterPosition)
    }
}

