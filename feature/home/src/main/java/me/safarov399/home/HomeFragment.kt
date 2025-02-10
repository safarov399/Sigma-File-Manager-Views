package me.safarov399.home

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import dagger.hilt.android.AndroidEntryPoint
import me.safarov399.common.FileConstants.DATE_SORTING_TYPE
import me.safarov399.common.FileConstants.FILE_TYPE
import me.safarov399.common.FileConstants.FOLDER_TYPE
import me.safarov399.common.FileConstants.NAME_SORTING_TYPE
import me.safarov399.common.FileConstants.SIZE_SORTING_TYPE
import me.safarov399.common.FileConstants.TYPE_SORTING_TYPE
import me.safarov399.core.PermissionConstants
import me.safarov399.core.adapter.FileFolderAdapter
import me.safarov399.core.adapter.OnClickListener
import me.safarov399.core.base.BaseFragment
import me.safarov399.core.storage.StorageConstants.DEFAULT_DIRECTORY
import me.safarov399.core.storage.StorageConstants.RESTRICTED_DIRECTORIES
import me.safarov399.domain.models.adapter.FileFolderModel
import me.safarov399.domain.models.adapter.FileModel
import me.safarov399.domain.models.adapter.FolderModel
import me.safarov399.home.databinding.FragmentHomeBinding
import me.safarov399.uikit.custom_views.dialogs.CreateFileFolderDialog
import me.safarov399.uikit.custom_views.dialogs.PermissionDialog
import java.io.File


@AndroidEntryPoint
class HomeFragment : BaseFragment<FragmentHomeBinding, HomeViewModel, HomeUiState, HomeEffect, HomeEvent>() {

    private var fileFolderAdapter: FileFolderAdapter? = null
    private var rv: RecyclerView? = null

    private var backPressCallback: OnBackPressedCallback? = null
    private var currentPath = DEFAULT_DIRECTORY

    private var isClickable = true
    private var areAllFabVisible = false

    private val requestAndroid10AndBelowPermissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        for (permission in permissions) {
            if (!permission.value) {
                break
            }
        }

        if (!checkStoragePermissions()) {
            if (!shouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE) || !shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                goToSettingsDialog()
            } else {
                showPermissionRequestDialog()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private val requestAndroid11AndHigherPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (!Environment.isExternalStorageManager()) {
            showPermissionRequestDialog()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        configureViews()
        if (!checkStoragePermissions()) {
            showPermissionRequestDialog()
        }
        handleBackPress()
    }

    override fun onResume() {
        super.onResume()
        if (checkStoragePermissions()) {
            postEvent(HomeEvent.ChangePath(currentPath))
        }
    }

    override fun onEffectUpdate(effect: HomeEffect) {
        when (effect) {
            HomeEffect.FileAlreadyExists -> Toast.makeText(requireActivity(), getString(me.safarov399.common.R.string.file_already_exists), Toast.LENGTH_SHORT).show()
            is HomeEffect.FileCreated -> Toast.makeText(requireActivity(), getString(me.safarov399.common.R.string.file_created, effect.name, effect.path), Toast.LENGTH_LONG).show()
            HomeEffect.FolderAlreadyExists -> Toast.makeText(requireActivity(), getString(me.safarov399.common.R.string.folder_already_exists), Toast.LENGTH_SHORT).show()
            is HomeEffect.FolderCreated -> Toast.makeText(requireActivity(), getString(me.safarov399.common.R.string.folder_created, effect.name, effect.path), Toast.LENGTH_LONG).show()
        }
    }

    override fun onStateUpdate(state: HomeUiState) {
        currentPath = state.currentPath
        backPressCallback?.isEnabled = currentPath != DEFAULT_DIRECTORY
        fileFolderAdapter?.submitList(state.currentFileFolders)

        fileFolderAdapter?.setOnClickListener(object : OnClickListener {
            override fun onClick(position: Int, model: FileFolderModel) {
                hideFab()

                if (!isClickable) return // Ignore clicks if interaction is disabled
                isClickable = false

                val folderName = (model as FolderModel).name
                val temporaryCurrentPath = if (state.currentPath.endsWith("/")) {
                    state.currentPath + folderName
                } else {
                    "${state.currentPath}/$folderName"
                }

                if (temporaryCurrentPath !in RESTRICTED_DIRECTORIES) {
                    postEvent(HomeEvent.ChangePath(temporaryCurrentPath))
                    state.currentPath = temporaryCurrentPath
                }
                binding.pathTv.text = state.currentPath

                currentPath = state.currentPath

                // Re-enable interaction after a short delay
                binding.root.postDelayed({ isClickable = true }, 500)
            }
        }, object : OnClickListener {
            override fun onClick(position: Int, model: FileFolderModel) {
                hideFab()

                if (!isClickable) return // Ignore clicks if interaction is disabled
                isClickable = false

                val file = File(state.currentPath, (model as FileModel).name)
                val fileExtension = file.extension
                val uri = FileProvider.getUriForFile(requireContext(), requireActivity().packageName + ".fileprovider", file)

                if (fileExtension == "apk") {
                    if (requireActivity().packageManager.canRequestPackageInstalls()) {
                        installApk(file.absolutePath)
                    } else {
                        requestApkInstallPermission()
                    }
                } else {
                    val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension) ?: "*/*"
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, mimeType)
                        flags = Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    }

                    startActivity(intent)
                }
                isClickable = true // Reset the flag after handling the intent
            }
        })

        binding.homeNavUp.setOnClickListener {
            if (state.currentPath != DEFAULT_DIRECTORY) {
                hideFab()
                val nextPath = state.currentPath.substringBeforeLast("/")
                postEvent(
                    HomeEvent.ChangePath(
                        nextPath
                    )
                )
                binding.pathTv.text = nextPath
            }
        }
    }

    private fun configureViews() {
        rv = binding.homeRv
        fileFolderAdapter = FileFolderAdapter()
        rv?.adapter = fileFolderAdapter
        hideFab()

        binding.apply {
            homeCreateFileFab.imageTintList = null
            homeCreateFolderFab.imageTintList = null

            pathTv.text = DEFAULT_DIRECTORY

            homeCreateEfab.setOnClickListener {
                if (areAllFabVisible) {
                    hideFab()
                } else {
                    showFab()
                }
            }

            homeCreateFolderFab.setOnClickListener {
                showCreateFileFolderDialog(FOLDER_TYPE)
            }

            homeCreateFileFab.setOnClickListener {
                showCreateFileFolderDialog(FILE_TYPE)
            }
            homeThreeDotsIv.setOnClickListener {
                showSortingPopup(it)
            }
        }
    }

    private fun showSortingPopup(view: View) {
        val popup = PopupMenu(requireActivity(), view)
        val popupMenuInflater = popup.menuInflater
        popupMenuInflater.inflate(me.safarov399.common.R.menu.sort_menu, popup.menu)
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                me.safarov399.common.R.id.sort_name -> {
                    postEvent(HomeEvent.ChangeSortingType(NAME_SORTING_TYPE))
                    postEvent(HomeEvent.ChangePath(currentPath))
                }

                me.safarov399.common.R.id.sort_date -> {
                    postEvent(HomeEvent.ChangeSortingType(DATE_SORTING_TYPE))
                    postEvent(HomeEvent.ChangePath(currentPath))
                }

                me.safarov399.common.R.id.sort_size -> {
                    postEvent(HomeEvent.ChangeSortingType(SIZE_SORTING_TYPE))
                    postEvent(HomeEvent.ChangePath(currentPath))
                }

                me.safarov399.common.R.id.sort_type -> {
                    postEvent(HomeEvent.ChangeSortingType(TYPE_SORTING_TYPE))
                    postEvent(HomeEvent.ChangePath(currentPath))
                }
            }
            true
        }
        popup.show()
    }

    private fun hideFab() {
        binding.apply {
            homeCreateFileFab.hide()
            homeCreateFolderFab.hide()
            homeCreateFolderTv.visibility = View.GONE
            homeCreateFileTv.visibility = View.GONE
            homeCreateEfab.shrink()
        }
        areAllFabVisible = false
    }

    private fun showFab() {
        binding.apply {
            homeCreateFileFab.show()
            homeCreateFolderFab.show()
            homeCreateFolderTv.visibility = View.VISIBLE
            homeCreateFileTv.visibility = View.VISIBLE
            homeCreateEfab.extend()
        }
        areAllFabVisible = true
    }


    private fun handleBackPress() {
        backPressCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentPath != DEFAULT_DIRECTORY) {
                    postEvent(
                        HomeEvent.ChangePath(
                            currentPath.substringBeforeLast("/")
                        )
                    )
                    binding.pathTv.text = currentPath.substringBeforeLast("/")
                } else {
                    isEnabled = false // Allow the system to handle back press
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backPressCallback as OnBackPressedCallback)
    }

    private fun showCreateFileFolderDialog(itemType: Int) {
        CreateFileFolderDialog(requireActivity()).apply {
            if (itemType == FILE_TYPE) {
                setTitle(getString(me.safarov399.common.R.string.create_new_file))
                setHint(getString(me.safarov399.common.R.string.enter_file_name))
                setConfirmAction {
                    val fileToCreate = findViewById<TextInputEditText>(me.safarov399.uikit.R.id.cff_name_tiet).text.toString()
                    postEvent(HomeEvent.CreateObject(fileToCreate, currentPath, FILE_TYPE))
                    dismiss()

                    /**
                     * The commented out code below is a safe-guard mechanism to prevent users from creating files with only spaces or newlines. However, since it has a possibility of not being optimal for some power users (for whatever reason), I am not going to implement it at the moment. Yet, for the sake of someone who may not like such behaviour and want to turn it on but does not want to construct the logic to do so, just comment out the last two lines and uncomment the code below. I will create a toggle in settings screen to switch this behaviour on and off later on.
                     */
//                    if (fileToCreate.isNotBlank()) {
//                        postEvent(HomeEvent.CreateObject(fileToCreate, currentPath, FILE_TYPE))
//                        dismiss()
//                    } else {
//                        Toast.makeText(requireActivity(), getString(me.safarov399.common.R.string.file_name_must_not_empty), Toast.LENGTH_SHORT).show()
//                    }
                }
            } else {
                setTitle(getString(me.safarov399.common.R.string.create_new_folder))
                setHint(getString(me.safarov399.common.R.string.enter_folder_name))
                setConfirmAction {
                    val fileToCreate = findViewById<TextInputEditText>(me.safarov399.uikit.R.id.cff_name_tiet).text.toString()
                    postEvent(HomeEvent.CreateObject(fileToCreate, currentPath, FOLDER_TYPE))
                    dismiss()

                    /**
                     * The commented out code below is a safe-guard mechanism to prevent users from creating folders with only spaces or newlines. However, since it has a possibility of not being optimal for some power users (for whatever reason), I am not going to implement it at the moment. Yet, for the sake of someone who may not like such behaviour and want to turn it on but does not want to construct the logic to do so, just comment out the last two lines and uncomment the code below. I will create a toggle in settings screen to switch this behaviour on and off later on.
                     */
//                    if (fileToCreate.isNotBlank()) {
//                        postEvent(HomeEvent.CreateObject(fileToCreate, currentPath, FOLDER_TYPE))
//                        dismiss()
//                    } else {
//                        Toast.makeText(requireActivity(), getString(me.safarov399.common.R.string.folder_name_must_not_empty), Toast.LENGTH_SHORT).show()
//                    }
                }
            }
            setCancelAction {
                dismiss()
            }
            show()
        }
    }

    private fun showPermissionRequestDialog() {
        PermissionDialog(requireActivity()).apply {
            setTitle(getString(me.safarov399.common.R.string.permission_dialog_title))
            setDescription(getString(me.safarov399.common.R.string.permission_dialog_description))
            setConfirmButtonText(getString(me.safarov399.common.R.string.ok))
            setCancelButtonText(getString(me.safarov399.common.R.string.cancel))
            setConfirmationOnClickListener {
                dismiss()
                requestStoragePermission()
            }
            show()
        }
    }


    private fun requestApkInstallPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${requireActivity().packageName}")
        }
        startActivity(intent)
    }


    private fun installApk(apkUri: String) {
        val apkFile = File(apkUri)
        if (!apkFile.exists()) {
            Log.e("APKInstall", "APK file does not exist: $apkUri")
            return
        }

        val fileUri: Uri = FileProvider.getUriForFile(
            requireActivity(),
            "${requireActivity().packageName}.fileprovider",
            apkFile
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(fileUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.e("APKInstall", "No activity found to handle APK installation", e)
        }
    }


    private fun goToSettingsDialog() {
        PermissionDialog(requireActivity()).apply {
            setTitle(getString(me.safarov399.common.R.string.not_granted_title))
            setDescription(getString(me.safarov399.common.R.string.not_granted_description))
            setConfirmButtonText(getString(me.safarov399.common.R.string.not_granted_confirm))
            setCancelButtonText(getString(me.safarov399.common.R.string.not_granted_cancel))
            setConfirmationOnClickListener {
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + requireActivity().packageName)
                )
                startActivity(intent)
                dismiss()
                activity?.finish()
            }
            show()
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requestStoragePermissionAndroid11AndHigher()
        } else {
            requestAndroid10AndBelowPermissionsLauncher.launch(PermissionConstants.requiredPermissionsAndroid10AndBelow)
        }
    }

    private fun checkStoragePermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            //Android is 11 (R) or above
            return Environment.isExternalStorageManager()
        } else {
            //Below android 11
            val write = ContextCompat.checkSelfPermission(requireActivity(), Manifest.permission.WRITE_EXTERNAL_STORAGE)
            val read = ContextCompat.checkSelfPermission(requireActivity(), Manifest.permission.READ_EXTERNAL_STORAGE)

            return read == PackageManager.PERMISSION_GRANTED && write == PackageManager.PERMISSION_GRANTED
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun requestStoragePermissionAndroid11AndHigher() {
        val uri = Uri.fromParts("package", requireActivity().packageName, null)
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri
        )
        requestAndroid11AndHigherPermissionLauncher.launch(intent)
    }

    override fun getViewModelClass(): Class<HomeViewModel> = HomeViewModel::class.java

    override val getViewBinding: (LayoutInflater, ViewGroup?, Boolean) -> FragmentHomeBinding = { inflater, viewGroup, value ->
        FragmentHomeBinding.inflate(inflater, viewGroup, value)
    }

    override fun onDestroy() {
        super.onDestroy()
        rv = null
        fileFolderAdapter = null
        backPressCallback = null
    }
}