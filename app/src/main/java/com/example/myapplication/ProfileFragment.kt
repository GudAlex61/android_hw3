// \app\src\main\java\com\example\myapplication\ProfileFragment.kt
package com.example.myapplication

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.myapplication.auth.SessionManager
import com.example.myapplication.data.AppDatabase
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.regex.Pattern
import com.example.myapplication.auth.LoginActivity

class ProfileFragment : Fragment() {

    private lateinit var avatar: ImageView
    private lateinit var progressBar: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var passportInput: EditText
    private lateinit var toolbar: Toolbar
    private lateinit var settingsButton: ImageButton

    private lateinit var emailText: TextView
    private lateinit var fullNameInput: EditText
    private lateinit var birthDateInput: EditText
    private lateinit var saveButton: Button
    private lateinit var logoutButton: Button

    private lateinit var viewModel: ProfileViewModel
    private lateinit var sessionManager: SessionManager

    private val datePattern = Pattern.compile("^\\d{2}\\.\\d{2}\\.\\d{4}$")

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> loadImageFromUri(uri) }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            openGallery()
        } else {
            Toast.makeText(
                requireContext(),
                getString(R.string.request_permission_for_photo),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_profile, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sessionManager = SessionManager(requireContext())
        val dao = AppDatabase.getDatabase(requireContext()).userDao()
        val repository = ProfileRepository(dao)

        viewModel = ProfileViewModel(
            repository = repository,
            sessionManager = sessionManager,
            errorLoad = getString(R.string.error_load)
        )

        bindViews(view)
        setupToolbar()
        applyWindowInsets(view)
        setupInputFormatters()
        setupClickListeners()
        loadSavedAvatarLocally()
        observeUiState()

        viewModel.loadProfile()
    }

    private fun bindViews(view: View) {
        avatar = view.findViewById(R.id.Avatar)
        progressBar = view.findViewById(R.id.progressBar)
        errorText = view.findViewById(R.id.errorText)
        passportInput = view.findViewById(R.id.passportInput)
        toolbar = view.findViewById(R.id.toolbar)
        settingsButton = view.findViewById(R.id.settingsButton)

        emailText = view.findViewById(R.id.emailText)
        fullNameInput = view.findViewById(R.id.fullNameInput)
        birthDateInput = view.findViewById(R.id.birthDateInput)
        saveButton = view.findViewById(R.id.saveButton)
        logoutButton = view.findViewById(R.id.logoutButton)
    }

    private fun setupToolbar() {
        (requireActivity() as AppCompatActivity).setSupportActionBar(toolbar)
        (requireActivity() as AppCompatActivity).supportActionBar?.setDisplayShowTitleEnabled(false)
    }

    private fun applyWindowInsets(view: View) {
        val initialPaddingTop = view.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = initialPaddingTop + systemBars.top)
            insets
        }
    }

    private fun setupInputFormatters() {
        birthDateInput.addTextChangedListener(object : TextWatcher {
            private var current = ""

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (s.toString() != current) {
                    val clean = s.toString().replace("\\D".toRegex(), "")
                    val length = clean.length

                    var isValid = true
                    if (clean.isNotEmpty()) {
                        if (clean[0] > '3') isValid = false
                        if (length >= 2) {
                            val day = clean.substring(0, 2).toInt()
                            if (day !in 1..31) isValid = false
                        }
                        if (length >= 3 && clean[2] > '1') isValid = false
                        if (length >= 4) {
                            val month = clean.substring(2, 4).toInt()
                            if (month !in 1..12) isValid = false
                        }
                        if (length >= 5 && clean[4] !in '1'..'2') isValid = false
                    }

                    if (!isValid) {
                        birthDateInput.removeTextChangedListener(this)
                        birthDateInput.setText(current)
                        birthDateInput.setSelection(current.length)
                        birthDateInput.addTextChangedListener(this)
                        return
                    }

                    var formatted = ""
                    if (length > 0) {
                        formatted = clean.substring(0, minOf(length, 2))
                        if (length > 2) {
                            formatted += "." + clean.substring(2, minOf(length, 4))
                            if (length > 4) {
                                formatted += "." + clean.substring(4, minOf(length, 8))
                            }
                        }
                    }

                    current = formatted
                    birthDateInput.removeTextChangedListener(this)
                    birthDateInput.setText(formatted)
                    birthDateInput.setSelection(formatted.length)
                    birthDateInput.addTextChangedListener(this)
                }
            }
        })
    }

    private fun setupClickListeners() {
        settingsButton.setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }

        avatar.setOnClickListener {
            checkPermissionAndOpenGallery()
        }

        saveButton.setOnClickListener {
            saveProfileChanges()
        }

        logoutButton.setOnClickListener {
            sessionManager.logout()

            val intent = Intent(requireContext(), LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)

            requireActivity().finishAffinity()
        }
    }

    private fun saveProfileChanges() {
        val fullName = fullNameInput.text.toString().trim()
        val birthDate = birthDateInput.text.toString().trim()
        val passport = passportInput.text.toString().trim()

        // Валидация ФИО
        if (fullName.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.profile_error_empty_fullname), Toast.LENGTH_SHORT).show()
            fullNameInput.error = getString(R.string.profile_hint_required_field)
            fullNameInput.requestFocus()
            return
        }

        if (birthDate.isNotEmpty() && !datePattern.matcher(birthDate).matches()) {
            Toast.makeText(requireContext(), getString(R.string.profile_error_invalid_date_format), Toast.LENGTH_SHORT).show()
            birthDateInput.error = getString(R.string.profile_hint_date_example)
            birthDateInput.requestFocus()
            return
        }

        if (passport.isNotEmpty() && (passport.length !in 6..20)) {
            Toast.makeText(requireContext(), getString(R.string.profile_error_invalid_passport), Toast.LENGTH_SHORT)
                .show()
            passportInput.error = getString(R.string.profile_hint_passport_length)
            passportInput.requestFocus()
            return
        }

        viewModel.updateProfile(
            fullName = fullName,
            birthDate = birthDate.ifEmpty { null },
            passportNumber = passport.ifEmpty { null },
            onSuccess = {
                Toast.makeText(requireContext(), getString(R.string.profile_success_updated), Toast.LENGTH_SHORT).show()
                val imm =
                    requireContext().getSystemService(Activity.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(requireView().windowToken, 0)
            },
            onError = { error ->
                Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
            })
    }


    private fun loadSavedAvatarLocally() {
        viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = withContext(IO) {
                viewModel.loadSavedAvatar(requireContext())
            }
            bitmap?.let { avatar.setImageBitmap(it) }
        }
    }

    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state) {
                        is ProfileUiState.Loading -> showLoading()
                        is ProfileUiState.Content -> showProfile(state.profile)
                        is ProfileUiState.Error -> showError(state.message)
                    }
                }
            }
        }
    }

    private fun showLoading() {
        progressBar.visibility = View.VISIBLE
        errorText.visibility = View.GONE
        toggleContentVisibility(View.GONE)
    }

    private fun showError(message: String) {
        progressBar.visibility = View.GONE
        errorText.visibility = View.VISIBLE
        errorText.text = message
        toggleContentVisibility(View.GONE)
    }

    private fun toggleContentVisibility(visibility: Int) {
        avatar.visibility = visibility
        emailText.visibility = visibility
        fullNameInput.visibility = visibility
        birthDateInput.visibility = visibility
        passportInput.visibility = visibility
        saveButton.visibility = visibility
    }

    private fun showProfile(profile: UserProfile) {
        progressBar.visibility = View.GONE
        errorText.visibility = View.GONE
        toggleContentVisibility(View.VISIBLE)

        emailText.text = profile.email
        fullNameInput.setText(profile.fullName)
        birthDateInput.setText(profile.birthDate ?: "")
        passportInput.setText(profile.passportNumber ?: "")
    }

    private fun checkPermissionAndOpenGallery() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_IMAGES
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        when {
            ContextCompat.checkSelfPermission(
                requireContext(), permission
            ) == PackageManager.PERMISSION_GRANTED -> {
                openGallery()
            }

            shouldShowRequestPermissionRationale(permission) -> {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.request_permission_for_photo),
                    Toast.LENGTH_LONG
                ).show()
                requestPermissionLauncher.launch(permission)
            }

            else -> {
                requestPermissionLauncher.launch(permission)
            }
        }
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_PICK)
        intent.type = "image/*"
        galleryLauncher.launch(intent)
    }

    private fun loadImageFromUri(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val bitmap = withContext(IO) {
                    val inputStream = requireContext().contentResolver.openInputStream(uri)
                    BitmapFactory.decodeStream(inputStream)
                }
                bitmap?.let {
                    avatar.setImageBitmap(it)
                    viewModel.saveAvatarFromBitmap(requireContext(), it)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(
                    requireContext(), getString(R.string.error_loading_photo), Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}