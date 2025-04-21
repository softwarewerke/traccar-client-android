/*
 * Copyright 2012 - 2023 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.client

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.SharedPreferences.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.webkit.URLUtil
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.preference.EditTextPreference
import androidx.preference.EditTextPreferenceDialogFragmentCompat
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.TwoStatePreference
import dev.doubledot.doki.ui.DokiActivity
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.*

class MainFragment : PreferenceFragmentCompat(), OnSharedPreferenceChangeListener {

    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var alarmManager: AlarmManager
    private lateinit var alarmIntent: PendingIntent
    private var requestingPermissions: Boolean = false

    @SuppressLint("UnspecifiedImmutableFlag")
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setHasOptionsMenu(true)
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
        setPreferencesFromResource(R.xml.preferences, rootKey)
        initPreferences()

        findPreference<Preference>(KEY_DEVICE)?.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, newValue ->
                newValue != null && newValue != ""
            }
        findPreference<Preference>(KEY_URL)?.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, newValue ->
                newValue != null && validateServerURL(newValue.toString())
            }
        findPreference<Preference>(KEY_INTERVAL)?.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, newValue ->
                try {
                    newValue != null && (newValue as String).toInt() > 0
                } catch (e: NumberFormatException) {
                    Log.w(TAG, e)
                    false
                }
            }
        val numberValidationListener = Preference.OnPreferenceChangeListener { _, newValue ->
            try {
                newValue != null && (newValue as String).toInt() >= 0
            } catch (e: NumberFormatException) {
                Log.w(TAG, e)
                false
            }
        }
        findPreference<Preference>(KEY_DISTANCE)?.onPreferenceChangeListener =
            numberValidationListener
        findPreference<Preference>(KEY_ANGLE)?.onPreferenceChangeListener = numberValidationListener

        alarmManager = requireActivity().getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val originalIntent = Intent(activity, AutostartReceiver::class.java)
        originalIntent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        alarmIntent = PendingIntent.getBroadcast(activity, 0, originalIntent, flags)

        if (sharedPreferences.getBoolean(KEY_STATUS, false)) {
            startTrackingService(checkPermission = true, initialPermission = false)
        }
    }

    class NumericEditTextPreferenceDialogFragment : EditTextPreferenceDialogFragmentCompat() {

        override fun onBindDialogView(view: View) {
            val editText = view.findViewById<EditText>(android.R.id.edit)
            editText.inputType = InputType.TYPE_CLASS_NUMBER
            super.onBindDialogView(view)
        }

        companion object {
            fun newInstance(key: String?): NumericEditTextPreferenceDialogFragment {
                val fragment = NumericEditTextPreferenceDialogFragment()
                val bundle = Bundle()
                bundle.putString(ARG_KEY, key)
                fragment.arguments = bundle
                return fragment
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (listOf(KEY_INTERVAL, KEY_DISTANCE, KEY_ANGLE).contains(preference.key)) {
            val f: EditTextPreferenceDialogFragmentCompat =
                NumericEditTextPreferenceDialogFragment.newInstance(preference.key)
            f.setTargetFragment(this, 0)
            f.show(requireFragmentManager(), "androidx.preference.PreferenceFragment.DIALOG")
        } else {
            super.onDisplayPreferenceDialog(preference)
        }
    }

    override fun onStart() {
        super.onStart()
        if (requestingPermissions) {
            requestingPermissions = BatteryOptimizationHelper().requestException(requireContext())
        }
    }

    override fun onResume() {
        super.onResume()
        sharedPreferences.registerOnSharedPreferenceChangeListener(this)
        findPreference<Preference>(KEY_STATUS)?.isEnabled = false
        hideMyConfigPrefs()
        loadMyConfigPrefs()
    }

    override fun onPause() {
        super.onPause()
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun setPreferencesEnabled(enabled: Boolean) {
        findPreference<Preference>(KEY_DEVICE)?.isEnabled = enabled
        findPreference<Preference>(KEY_URL)?.isEnabled = enabled
        findPreference<Preference>(KEY_INTERVAL)?.isEnabled = enabled
        findPreference<Preference>(KEY_DISTANCE)?.isEnabled = enabled
        findPreference<Preference>(KEY_ANGLE)?.isEnabled = enabled
        findPreference<Preference>(KEY_ACCURACY)?.isEnabled = enabled
        findPreference<Preference>(KEY_BUFFER)?.isEnabled = enabled
        findPreference<Preference>(KEY_WAKELOCK)?.isEnabled = enabled
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == KEY_STATUS) {
            if (sharedPreferences?.getBoolean(KEY_STATUS, false) == true) {
                startTrackingService(checkPermission = true, initialPermission = false)
            } else {
                stopTrackingService()
            }
            (requireActivity().application as MainApplication).handleRatingFlow(requireActivity())
        } else if (key == KEY_DEVICE) {
            findPreference<Preference>(KEY_DEVICE)?.summary =
                sharedPreferences?.getString(KEY_DEVICE, null)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.main, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.status) {
            startActivity(Intent(activity, StatusActivity::class.java))
            return true
        } else if (item.itemId == R.id.info) {
            DokiActivity.start(requireContext())
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun initPreferences() {
        PreferenceManager.setDefaultValues(requireActivity(), R.xml.preferences, false)
        if (!sharedPreferences.contains(KEY_DEVICE)) {
            val id = (Random().nextInt(900000) + 100000).toString()
            sharedPreferences.edit().putString(KEY_DEVICE, id).apply()
            findPreference<EditTextPreference>(KEY_DEVICE)?.text = id
        }
        findPreference<Preference>(KEY_DEVICE)?.summary =
            sharedPreferences.getString(KEY_DEVICE, null)
    }

    private fun showBackgroundLocationDialog(context: Context, onSuccess: () -> Unit) {
        val builder = AlertDialog.Builder(context)
        val option = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.backgroundPermissionOptionLabel
        } else {
            context.getString(R.string.request_background_option)
        }
        builder.setMessage(context.getString(R.string.request_background, option))
        builder.setPositiveButton(android.R.string.ok) { _, _ -> onSuccess() }
        builder.setNegativeButton(android.R.string.cancel, null)
        builder.show()
    }

    private fun startTrackingService(checkPermission: Boolean, initialPermission: Boolean) {
        var permission = initialPermission
        if (checkPermission) {
            val requiredPermissions: MutableSet<String> = HashSet()
            if (ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            permission = requiredPermissions.isEmpty()
            if (!permission) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    requestPermissions(
                        requiredPermissions.toTypedArray(),
                        PERMISSIONS_REQUEST_LOCATION
                    )
                }
                return
            }
        }
        if (permission) {
            setPreferencesEnabled(false)
            ContextCompat.startForegroundService(
                requireContext(),
                Intent(activity, TrackingService::class.java)
            )
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                alarmManager.setInexactRepeating(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    ALARM_MANAGER_INTERVAL.toLong(), ALARM_MANAGER_INTERVAL.toLong(), alarmIntent
                )
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestingPermissions = true
                showBackgroundLocationDialog(requireContext()) {
                    requestPermissions(
                        arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                        PERMISSIONS_REQUEST_BACKGROUND_LOCATION
                    )
                }
            } else {
                requestingPermissions =
                    BatteryOptimizationHelper().requestException(requireContext())
            }
        } else {
            sharedPreferences.edit().putBoolean(KEY_STATUS, false).apply()
            val preference = findPreference<TwoStatePreference>(KEY_STATUS)
            preference?.isChecked = false
        }
    }

    private fun stopTrackingService() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            alarmManager.cancel(alarmIntent)
        }
        requireActivity().stopService(Intent(activity, TrackingService::class.java))
        setPreferencesEnabled(true)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (requestCode == PERMISSIONS_REQUEST_LOCATION) {
            var granted = true
            for (result in grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    granted = false
                    break
                }
            }
            startTrackingService(false, granted)
        }
    }

    private fun validateServerURL(userUrl: String): Boolean {
        val port = Uri.parse(userUrl).port
        if (
            URLUtil.isValidUrl(userUrl) &&
            (port == -1 || port in 1..65535) &&
            (URLUtil.isHttpUrl(userUrl) || URLUtil.isHttpsUrl(userUrl))
        ) {
            return true
        }
        Toast.makeText(activity, R.string.error_msg_invalid_url, Toast.LENGTH_LONG).show()
        return false
    }

    companion object {
        private val TAG = MainFragment::class.java.simpleName
        private const val ALARM_MANAGER_INTERVAL = 15000
        const val KEY_DEBUG = "debug"
        const val KEY_DEVICE = "id"
        const val KEY_URL = "url"
        const val KEY_INTERVAL = "interval"
        const val KEY_DISTANCE = "distance"
        const val KEY_ANGLE = "angle"
        const val KEY_ACCURACY = "accuracy"
        const val KEY_STATUS = "status"
        const val KEY_BUFFER = "buffer"
        const val KEY_WAKELOCK = "wakelock"
        private const val PERMISSIONS_REQUEST_LOCATION = 2
        private const val PERMISSIONS_REQUEST_BACKGROUND_LOCATION = 3
    }

    private enum class PrefType {
        STRING, NUMBER, BOOLEAN
    }

    private enum class SettingsAndroid(val pref: String, val type: PrefType) {
        SETTING_ANDROID_DEBUG(KEY_DEBUG, PrefType.BOOLEAN),
        SETTING_ANDROID_INTERVAL(KEY_INTERVAL, PrefType.NUMBER),
        SETTING_ANDROID_ACCURACY(KEY_ACCURACY, PrefType.NUMBER),
        SETTING_ANDROID_DISTANCE(KEY_DISTANCE, PrefType.NUMBER),
        SETTING_ANDROID_ANGLE(KEY_ANGLE, PrefType.NUMBER),
        SETTING_ANDROID_BUFFER(KEY_BUFFER, PrefType.BOOLEAN),
        SETTING_ANDROID_WAKELOCK(KEY_WAKELOCK, PrefType.BOOLEAN)
    }

    private fun initMyConfigApi(url: String?): MyConfigService {
        val retrofit = Retrofit.Builder()
            .baseUrl(url)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        return retrofit.create(MyConfigService::class.java)
    }

    private fun loadMyConfigPrefs() {

        var myConfigService = initMyConfigApi(sharedPreferences.getString(KEY_URL,""));
        var device = sharedPreferences.getString(KEY_DEVICE, "") ?: ""
        val call = myConfigService.getMyConfig(device)
        call.enqueue(object : Callback<MyConfig> {

            override fun onResponse(call: Call<MyConfig>, response: Response<MyConfig>) {
                setMyConfigPrefs(response.body())
                StatusActivity.addMessage("Config retrieved")
                findPreference<Preference>(KEY_STATUS)?.isEnabled = true
            }

            override fun onFailure(call: Call<MyConfig>, t: Throwable) {
                Log.e(TAG, t.toString())
                StatusActivity.addMessage("[ERR] Cannot retrieve config: " + t.message)
            }
        })
    }

    private fun setMyConfigPrefs(myConfig: MyConfig?) {

        if (myConfig == null) {
            return
        }

        sharedPreferences.edit() {

            val debug = myConfig.getPref(SettingsAndroid.SETTING_ANDROID_DEBUG.name)
            if (debug is Boolean) {
                putBoolean(SettingsAndroid.SETTING_ANDROID_DEBUG.pref, debug)
                commit()
                if (debug) {
                    StatusActivity.addMessage("[DBG] Config ${SettingsAndroid.SETTING_ANDROID_DEBUG.pref} = ${debug}")
                }
            }

            for (s in SettingsAndroid.entries) {
                val x = myConfig.getPref(s.name)
                if (x != null) {
                    when (s.type) {
                        PrefType.STRING -> putString(s.pref, x as String)
                        PrefType.NUMBER -> putString(s.pref, (x as Double).toInt().toString())
                        PrefType.BOOLEAN -> putBoolean(s.pref, x as Boolean)
                        else -> Log.e(TAG, "no type found for: $s.name")
                    }
                    if (s != SettingsAndroid.SETTING_ANDROID_DEBUG && sharedPreferences.getBoolean(KEY_DEBUG, false)) {
                        StatusActivity.addMessage("[DBG] Config ${s.pref} = ${x}")
                    }
                    val p: EditTextPreference? = findPreference<EditTextPreference>(s.pref)
                    if (p != null) {
                        // Todo  p.text = x.toString()
                    }
                }
            }
            commit()
        }
    }

    private fun hideMyConfigPrefs() {
        val visible = true
        findPreference<Preference>(KEY_DEVICE)?.isVisible = true
        findPreference<Preference>(KEY_URL)?.isVisible = visible
        findPreference<Preference>(KEY_INTERVAL)?.isVisible = visible
        findPreference<Preference>(KEY_DISTANCE)?.isVisible = visible
        findPreference<Preference>(KEY_ANGLE)?.isVisible = visible
        findPreference<Preference>(KEY_ACCURACY)?.isVisible = visible
        findPreference<Preference>(KEY_BUFFER)?.isVisible = visible
        findPreference<Preference>(KEY_WAKELOCK)?.isVisible = visible
    }

    interface MyConfigService {
        @GET("/api/my-config")
        fun getMyConfig(@Query("uniqueId") uniqueId: String): Call<MyConfig>
    }

    data class MyConfig(
        val device: Device,
        val group: Group
    ) {

        fun getPref(key: String): Any? {
            var x: Any? = device.attributes.get(key)
            if (x == null) {
                x = group.attributes.get(key)
            }
            return x
        }
    }

    data class Device(
        val id: Int,
        val attributes: Map<String, Object>
    )

    data class Group(
        val id: Int,
        val attributes: Map<String, Object>
    )
}
