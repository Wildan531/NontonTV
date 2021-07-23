package net.harimurti.tv

import android.Manifest
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.DownloadManager
import android.app.UiModeManager
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.*
import android.os.Build.VERSION_CODES
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.volley.Request
import com.android.volley.RequestQueue
import com.android.volley.Response
import com.android.volley.VolleyError
import com.android.volley.toolbox.BaseHttpStack
import com.android.volley.toolbox.HurlStack
import com.android.volley.toolbox.StringRequest
import com.android.volley.toolbox.Volley
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import net.harimurti.tv.adapter.CategoryAdapter
import net.harimurti.tv.extra.Network
import net.harimurti.tv.extra.PlaylistHelper
import net.harimurti.tv.extra.Preferences
import net.harimurti.tv.extra.TLSSocketFactory
import net.harimurti.tv.model.GithubUser
import net.harimurti.tv.model.Playlist
import net.harimurti.tv.model.Release


open class MainActivity : AppCompatActivity() {
    private var doubleBackToExitPressedOnce = false
    private lateinit var layoutSettings: View
    private lateinit var layoutLoading: View
    private lateinit var layoutCustom: View
    private lateinit var swCustomPlaylist: SwitchCompat
    private lateinit var preferences: Preferences
    private lateinit var playlistHelper: PlaylistHelper
    private lateinit var volley: RequestQueue

    @SuppressLint("DefaultLocale")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uiModeManager = getSystemService(UI_MODE_SERVICE) as UiModeManager
        if (uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) {
            setTheme(R.style.AppThemeTv)
        }
        setContentView(R.layout.activity_main)

        askPermissions()
        preferences = Preferences(this)
        playlistHelper = PlaylistHelper(this)

        // define some view
        layoutLoading = findViewById(R.id.layout_loading)
        // layout settings
        layoutSettings = findViewById(R.id.layout_settings)
        layoutSettings.setOnClickListener {
            layoutSettings.visibility = View.GONE
        }
        // switch launch at boot
        val swLaunch = findViewById<SwitchCompat>(R.id.launch_at_boot)
        swLaunch.isChecked = preferences.isLaunchAtBoot
        swLaunch.setOnClickListener {
            preferences.isLaunchAtBoot = swLaunch.isChecked
        }
        // switch play last watched
        val swOpenLast = findViewById<SwitchCompat>(R.id.open_last_watched)
        swOpenLast.isChecked = preferences.isOpenLastWatched
        swOpenLast.setOnClickListener {
            preferences.isOpenLastWatched = swOpenLast.isChecked
        }
        // layout custom playlist
        layoutCustom = findViewById(R.id.layout_custom_playlist)
        layoutCustom.visibility = if (preferences.useCustomPlaylist()) View.VISIBLE else View.GONE
        // switch custom playlist
        swCustomPlaylist = findViewById(R.id.use_custom_playlist)
        swCustomPlaylist.isChecked = preferences.useCustomPlaylist()
        swCustomPlaylist.setOnClickListener {
            layoutCustom.visibility = if (swCustomPlaylist.isChecked) View.VISIBLE else View.GONE
            preferences.setUseCustomPlaylist(swCustomPlaylist.isChecked)
        }
        // edittext custom playlist
        val txtCustom = findViewById<EditText>(R.id.custom_playlist)
        txtCustom.setText(preferences.playlistExternal)
        txtCustom.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                preferences.playlistExternal = s.toString()
            }
        })
        // button reload playlist
        findViewById<View>(R.id.reload_playlist).setOnClickListener { updatePlaylist() }

        // volley library
        var stack: BaseHttpStack = HurlStack()
        try {
            val factory = TLSSocketFactory(this)
            factory.trustAllHttps()
            stack = HurlStack(null, factory)
        } catch (e: Exception) {
            Log.e("MainApp", "Could not trust all HTTPS connection!", e)
        } finally {
            volley = Volley.newRequestQueue(this, stack)
        }

        // playlist update
        if (Playlist.loaded == null) {
            updatePlaylist()
        } else {
            setPlaylistToViewPager(Playlist.loaded!!)
        }

        // check new release
        if (!preferences.isCheckedReleaseUpdate) {
            checkNewRelease()
        }

        // get contributors
        if (preferences.lastVersionCode != BuildConfig.VERSION_CODE || !preferences.isShowLessContributors) {
            preferences.lastVersionCode = BuildConfig.VERSION_CODE
            getContributors()
        }

        // launch player if openlastwatched is true
        val streamUrl = preferences.lastWatched
        if (preferences.isOpenLastWatched && streamUrl != "" && PlayerActivity.isFirst) {
            val intent = Intent(this, PlayerActivity::class.java)
            intent.putExtra("channel_url", streamUrl)
            this.startActivity(intent)
        }
    }

    private fun checkNewRelease() {
        val stringRequest = StringRequest(Request.Method.GET,
            getString(R.string.json_release),
            Response.Listener { response: String? ->
                try {
                    val release = Gson().fromJson(response, Release::class.java)
                    if (release.versionCode <= BuildConfig.VERSION_CODE) return@Listener
                    val message = StringBuilder(
                        String.format(
                            getString(R.string.message_update),
                            BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE,
                            release.versionName, release.versionCode
                        )
                    )
                    for (log in release.changelog) {
                        message.append(String.format(getString(R.string.message_update_changelog), log))
                    }
                    if (release.changelog.isEmpty()) {
                        message.append(getString(R.string.message_update_no_changelog))
                    }
                    showAlertUpdate(message.toString(), release.downloadUrl)
                } catch (e: Exception) {
                    Log.e("Volley", "Could not check new update!", e)
                }
            }, null
        )
        volley.add(stringRequest)
    }

    private fun getContributors() {
            val stringRequest = StringRequest(Request.Method.GET,
                getString(R.string.gh_contributors),
                { response: String? ->
                    try {
                        val users = Gson().fromJson(response, Array<GithubUser>::class.java)
                        val message = StringBuilder(getString(R.string.message_thanks_to))
                        for (user in users) {
                            message.append(user.login).append(", ")
                        }
                        if (users.isNotEmpty() && preferences.totalContributors < users.size) {
                            preferences.totalContributors = users.size
                            showAlertContributors(message.substring(0, message.length - 2))
                        }
                    } catch (e: Exception) {
                        Log.e("Volley", "Could not get contributors!", e)
                    }
                }, null
            )
            volley.add(stringRequest)
        }

    private fun updatePlaylist() {
        // from local storage
        if (playlistHelper.mode() == PlaylistHelper.MODE_LOCAL) {
            val local = playlistHelper.readLocal()
            if (local == null) {
                showAlertLocalError()
                return
            }
            setPlaylistToViewPager(local)
            return
        }

        // from internet
        val stringRequest = StringRequest(Request.Method.GET,
            playlistHelper.urlPath,
            { response: String? ->
                try {
                    val newPls = Gson().fromJson(response, Playlist::class.java)
                    playlistHelper.writeCache(response)
                    setPlaylistToViewPager(newPls)
                } catch (error: JsonSyntaxException) {
                    showAlertPlaylistError(error.message)
                }
            }
        ) { error: VolleyError ->
            var message = getString(R.string.something_went_wrong)
            if (error.networkResponse != null) {
                val errorcode = error.networkResponse.statusCode
                if (errorcode in 400..499) message =
                    String.format(getString(R.string.error_4xx), errorcode)
                if (errorcode in 500..599) message =
                    String.format(getString(R.string.error_5xx), errorcode)
            } else if (!Network(this).isConnected()) {
                message = getString(R.string.no_network)
            }
            showAlertPlaylistError(message)
        }
        volley.cache.clear()
        volley.add(stringRequest)
    }

    private fun setPlaylistToViewPager(newPls: Playlist) {
        val recyclerView = findViewById<View>(R.id.rv_category) as RecyclerView
        recyclerView.adapter = CategoryAdapter(newPls.categories)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))

        layoutLoading.visibility = View.GONE
        if (Playlist.loaded != newPls) Toast.makeText(this, R.string.playlist_updated, Toast.LENGTH_SHORT).show()
        Playlist.loaded = newPls
    }

    private fun showAlertLocalError() {
        askPermissions()
        val alert = AlertDialog.Builder(this)
        alert.setTitle(R.string.alert_title_playlist_error)
            .setMessage(R.string.local_playlist_read_error)
            .setCancelable(false)
            .setPositiveButton(R.string.dialog_retry) { _: DialogInterface?, _: Int -> updatePlaylist() }
            .setNegativeButton(getString(R.string.dialog_default)) { _: DialogInterface?, _: Int ->
                preferences.setUseCustomPlaylist(false)
                swCustomPlaylist.isChecked = false
                layoutCustom.visibility = View.GONE
                updatePlaylist()
            }
        alert.create().show()
    }

    private fun showAlertPlaylistError(error: String?) {
        val message = error ?: getString(R.string.something_went_wrong)
        val alert = AlertDialog.Builder(this)
        alert.setTitle(R.string.alert_title_playlist_error)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(R.string.dialog_retry) { _: DialogInterface?, _: Int -> updatePlaylist() }
        val cache = playlistHelper.readCache()
        if (cache != null) {
            alert.setNegativeButton(R.string.dialog_cached) { _: DialogInterface?, _: Int -> setPlaylistToViewPager(cache) }
        }
        alert.create().show()
    }

    private fun showAlertUpdate(message: String, fileUrl: String) {
        askPermissions()
        val alert = AlertDialog.Builder(this)
        alert.setTitle(R.string.alert_new_update)
            .setMessage(message)
            .setPositiveButton(R.string.dialog_download) { _: DialogInterface?, _: Int -> downloadFile(fileUrl) }
            .setNegativeButton(R.string.dialog_skip) { _: DialogInterface?, _: Int -> preferences.setLastCheckUpdate() }
        alert.create().show()
    }

    private fun showAlertContributors(message: String) {
        val alert = AlertDialog.Builder(this)
        alert.setTitle(R.string.alert_title_contributors)
            .setMessage(message)
            .setNeutralButton(R.string.dialog_telegram) { _: DialogInterface?, _: Int -> openWebsite(getString(R.string.telegram_group)) }
            .setNegativeButton(R.string.dialog_website) { _: DialogInterface?, _: Int -> openWebsite(getString(R.string.website)) }
            .setPositiveButton(if (preferences.isShowLessContributors) R.string.dialog_close else R.string.dialog_show_less) {
                    _: DialogInterface?, _: Int -> preferences.setShowLessContributors()
            }
        val dialog = alert.create()
        dialog.show()
        Handler(Looper.getMainLooper()).postDelayed({ dialog.dismiss() }, 10000)
    }

    private fun openWebsite(link: String) {
        startActivity(Intent(Intent.ACTION_VIEW).setData(Uri.parse(link)))
    }

    private fun downloadFile(url: String) {
        try {
            val uri = Uri.parse(url)
            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(uri)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, uri.lastPathSegment)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            dm.enqueue(request)
        } catch (e: Exception) {
            Toast.makeText(this, e.message, Toast.LENGTH_SHORT).show()
        }
    }

    @TargetApi(23)
    protected fun askPermissions() {
        if (Build.VERSION.SDK_INT < VERSION_CODES.M) return
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.MANAGE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.MANAGE_EXTERNAL_STORAGE
                ), 1000
            )
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return if (keyCode == KeyEvent.KEYCODE_MENU) {
            layoutSettings.visibility = View.VISIBLE
            true
        } else {
            super.onKeyUp(keyCode, event)
        }
    }

    override fun onBackPressed() {
        if (layoutSettings.visibility == View.VISIBLE) {
            layoutSettings.visibility = View.GONE
            return
        }
        if (doubleBackToExitPressedOnce) {
            super.onBackPressed()
            finish()
            return
        }
        doubleBackToExitPressedOnce = true
        Toast.makeText(this, getString(R.string.press_back_twice_exit_app), Toast.LENGTH_SHORT).show()
        Handler(Looper.getMainLooper()).postDelayed({ doubleBackToExitPressedOnce = false }, 2000)
    }
}