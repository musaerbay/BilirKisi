package com.bilirkisi.proje.fragments.profile

import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.core.widget.NestedScrollView
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.gson.Gson
import com.bilirkisi.proje.R
import com.bilirkisi.proje.databinding.ProfileFragmentBinding
import com.bilirkisi.proje.models.User
import com.bilirkisi.proje.ui.mainActivity.SharedViewModel
import com.bilirkisi.proje.util.CLICKED_USER
import com.bilirkisi.proje.util.LOGGED_USER
import com.bilirkisi.proje.util.LoadState
import com.bilirkisi.proje.util.eventbus_events.KeyboardEvent
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.bottom_sheet_profile_picture.view.*
import org.greenrobot.eventbus.EventBus
import java.io.ByteArrayOutputStream

const val SELECT_PROFILE_IMAGE_REQUEST = 5
const val REQUEST_IMAGE_CAPTURE = 6

class ProfileFragment : Fragment() {


    private lateinit var mBottomSheetBehavior: BottomSheetBehavior<NestedScrollView>
    lateinit var binding: ProfileFragmentBinding
    lateinit var adapter: FriendsAdapter

    companion object {
        fun newInstance() =
            ProfileFragment()
    }

    private lateinit var viewModel: ProfileViewModel
    private lateinit var sharedViewModel: SharedViewModel

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        activity?.title = "Profil"
        binding = DataBindingUtil.inflate(inflater, R.layout.profile_fragment, container, false)
        return binding.root
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        getActivity()?.navView?.visibility = View.GONE

        viewModel = ViewModelProviders.of(this).get(ProfileViewModel::class.java)
        sharedViewModel = ViewModelProviders.of(activity!!).get(SharedViewModel::class.java)

        // alt sayfay?? ayarla
        mBottomSheetBehavior = BottomSheetBehavior.from(binding.bottomSheet)


        // kullan??c??y?? payla????lan tercihlerden al
        val mPrefs: SharedPreferences = activity!!.getPreferences(MODE_PRIVATE)
        val gson = Gson()
        val json: String? = mPrefs.getString(LOGGED_USER, null)
        val loggedUser: User = gson.fromJson(json, User::class.java)
        // kullan??c?? ad??n?? ve e-postay?? ve biyografiyi g??ster
        binding.bioTextView.text = loggedUser.bio ?: "Hen??z bilgi yok"
        binding.email.text = loggedUser.email
        binding.name.text = loggedUser.username

        // Kullan??c?? Uzman-Standart oldu??unu profilde g??ster
        loggedUser.expertUser?.let {
            if(it)
                binding.expertUser.text="Uzman"
            else
                binding.expertUser.text="Standart"
        }

        // profil foto??raf??n?? indir
        setProfileImage(loggedUser.profile_picture_url)


        // adapt??r olu??turun ve geri d??n??????m maddesini i??leyin geri aramaya t??klay??n
        adapter = FriendsAdapter(object :
            FriendsAdapter.ItemClickCallback {
            override fun onItemClicked(clickedUser: User) {

                val clickedUserString = gson.toJson(clickedUser)

                var bundle = bundleOf(
                    CLICKED_USER to clickedUserString
                )

                findNavController().navigate(
                    R.id.action_profileFragment_to_differentUserProfile,
                    bundle
                )
            }
        })


        //oturum a??m???? kullan??c??n??n arkada??lar??n?? y??kle and show in recycler
        sharedViewModel.loadFriends(loggedUser).observe(viewLifecycleOwner, Observer { friendsList ->
            //y??klemeyi gizle
            binding.loadingFriendsImageView.visibility = View.GONE
            if (friendsList != null) {
                binding.friendsLayout.visibility = View.VISIBLE
                binding.noFriendsLayout.visibility = View.GONE
                showFriendsInRecycler(friendsList)
            } else {
                binding.friendsLayout.visibility = View.GONE
                binding.noFriendsLayout.visibility = View.VISIBLE
                binding.addFriendsButton.setOnClickListener {
                    this@ProfileFragment.findNavController()
                        .navigate(R.id.action_profileFragment_to_findUserFragment)
                }
            }

        })



        binding.bottomSheet.cameraButton.setOnClickListener {
            openCamera()
        }
        binding.bottomSheet.galleryButton.setOnClickListener {
            selectFromGallery()
        }

        binding.bottomSheet.hide.setOnClickListener {
            mBottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        }


        // bu d????me t??kland??????nda se??imin alt sayfas??n?? g??ster
        binding.profileImage.setOnClickListener { selectProfilePicture() }
        binding.cameraImageView.setOnClickListener { selectProfilePicture() }


        // hakk??nda t??klamas??n?? d??zenle
        binding.editTextview.setOnClickListener {
            if (binding.editTextview.text.equals(getString(R.string.edit))) {
                //show edit text to allow user to edit bio and change text view text to submit
                binding.editTextview.text = getString(R.string.submit)
                binding.editTextview.setTextColor(Color.GREEN)
                binding.bioTextView.visibility = View.GONE
                binding.newBioEditText.visibility = View.VISIBLE


            } else if (binding.editTextview.text.equals(getString(R.string.submit))) {
                //hide edit text and upload changes to user document
                binding.editTextview.text = getString(R.string.edit)
                binding.editTextview.setTextColor(Color.parseColor("#b39ddb"))
                binding.bioTextView.visibility = View.VISIBLE
                binding.bioTextView.text = binding.newBioEditText.text
                binding.newBioEditText.visibility = View.GONE
                EventBus.getDefault().post(KeyboardEvent())
                // kullan??c?? belgesine bio y??kle
                viewModel.updateBio(binding.newBioEditText.text.toString())

                // klavyeyi gizle
                EventBus.getDefault().post(KeyboardEvent())
            }
        }


    }

    private fun setProfileImage(profilePictureUrl: String?) {
        Glide.with(this).load(profilePictureUrl)
            .apply(
                RequestOptions()
                    .placeholder(R.drawable.loading_animation)
                    .error(R.drawable.anonymous_profile)
                    .circleCrop()
            )
            .into(binding.profileImage)
    }

    private fun showFriendsInRecycler(it: List<User>) {
        adapter.setDataSource(it)
        binding.friendsRecycler.adapter = adapter
        binding.friendsCountTextView.text = it.size.toString()
    }

    private fun setProfileImageLoadUi(it: LoadState?) {
        when (it) {

            LoadState.SUCCESS -> {
                binding.uploadProgressBar.visibility = View.GONE
                binding.uploadText.visibility = View.GONE
                binding.profileImage.alpha = 1f
            }
            LoadState.FAILURE -> {
                binding.uploadProgressBar.visibility = View.GONE
                binding.uploadText.visibility = View.GONE
                binding.profileImage.alpha = 1f
            }
            LoadState.LOADING -> {
                binding.uploadProgressBar.visibility = View.VISIBLE
                binding.uploadText.visibility = View.GONE
                binding.profileImage.alpha = .5f

            }
        }
    }


    private fun selectProfilePicture() {
        mBottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)


        mBottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED


        // galeriden resim se??menin sonucu
        if (requestCode == SELECT_PROFILE_IMAGE_REQUEST && data != null && resultCode == AppCompatActivity.RESULT_OK) {

            // se??ili resmi profil resmi g??r??n??m??nde ayarlay??n ve y??kleyin

            //foto??raf y??kleniyor
            viewModel.uploadProfileImageByUri(data.data)


        }


        // kamera g??r??nt??s?? ??ekmenin sonucu
        if (requestCode == REQUEST_IMAGE_CAPTURE && resultCode == AppCompatActivity.RESULT_OK) {
            val imageBitmap = data?.extras?.get("data") as Bitmap


            val baos = ByteArrayOutputStream()
            imageBitmap.compress(Bitmap.CompressFormat.JPEG, 100, baos)
            val byteArray = baos.toByteArray()


            //foto??raf y??kleniyor
            viewModel.uploadImageAsBytearray(byteArray)


        }

        // y??kleme s??ras??nda y??kleme d??zenini g??ster
        viewModel.uploadImageLoadStateMutableLiveData.observe(this, Observer { imageUploadState ->
            setProfileImageLoadUi(imageUploadState)
        })


        // profil resmi g??r??n??m??nde yeni resmi ayarla
        viewModel.newImageUriMutableLiveData.observe(this, Observer {
            setProfileImage(it.toString())
        })
    }


    private fun selectFromGallery() {
        var intent = Intent()
        intent.type = "image/*"
        intent.action = Intent.ACTION_GET_CONTENT
        startActivityForResult(
            Intent.createChooser(intent, "Select Picture"),
            SELECT_PROFILE_IMAGE_REQUEST
        )
    }


    private fun openCamera() {
        Intent(MediaStore.ACTION_IMAGE_CAPTURE).also { takePictureIntent ->
            takePictureIntent.resolveActivity(activity!!.packageManager)?.also {
                startActivityForResult(takePictureIntent,
                    REQUEST_IMAGE_CAPTURE
                )
            }
        }
    }


}

