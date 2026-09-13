package com.bintianqi.owndroid

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

class ApiReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val requestKey = intent.getStringExtra("key")
        var log = "OwnDroid API request received. action: ${intent.action}"
        val myApp = context.applicationContext as MyApplication
        val apiSettings = myApp.container.settingsRepo.data.api
        if (apiSettings.enabled && apiSettings.key == requestKey) {
            val app = intent.getStringExtra("package")
            val permission = intent.getStringExtra("permission")
            val restriction = intent.getStringExtra("restriction")
            val query = intent.getStringExtra("query")
            if (!app.isNullOrEmpty()) log += "\npackage: $app"
            if (!permission.isNullOrEmpty()) log += "\npermission: $permission"
            if (!restriction.isNullOrEmpty()) log += "\nrestriction: $restriction"
            if (!query.isNullOrEmpty()) log += "\nquery: $query"
            try {
                myApp.container.privilegeHelper.safeDpmCall {
                    @SuppressWarnings("NewApi")
                    when (intent.action?.removePrefix("com.bintianqi.owndroid.action.")) {
                        "GET" -> handleGet(intent, query, app, permission, restriction)

                        "HIDE" -> dpm.setApplicationHidden(dar, app, true)
                        "UNHIDE" -> dpm.setApplicationHidden(dar, app, false)
                        "SUSPEND" -> dpm.setPackagesSuspended(dar, arrayOf(app), true)
                        "UNSUSPEND" -> dpm.setPackagesSuspended(dar, arrayOf(app), false)
                        "DISABLE_METERED_DATA" -> {
                            dpm.setMeteredDataDisabledPackages(
                                dar, dpm.getMeteredDataDisabledPackages(dar) + app
                            )
                        }
                        "ENABLE_METERED_DATA" -> {
                            dpm.setMeteredDataDisabledPackages(
                                dar, dpm.getMeteredDataDisabledPackages(dar).filter { it != app }
                            )
                        }
                        "DISABLE_USER_CONTROL" -> {
                            dpm.setUserControlDisabledPackages(
                                dar, dpm.getUserControlDisabledPackages(dar) + app
                            )
                        }
                        "ENABLE_USER_CONTROL" -> {
                            dpm.setUserControlDisabledPackages(
                                dar, dpm.getUserControlDisabledPackages(dar).filter { it != app }
                            )
                        }

                        "BLOCK_UNINSTALL" -> {
                            dpm.setUninstallBlocked(dar, app, true)
                        }
                        "UNBLOCK_UNINSTALL" -> {
                            dpm.setUninstallBlocked(dar, app, false)
                        }

                        "CLEAR_APP_STORAGE" -> {
                            dpm.clearApplicationUserData(
                                dar, app!!, myApp.mainExecutor, { _, _ -> }
                            )
                        }

                        "ADD_USER_RESTRICTION" -> {
                            dpm.addUserRestriction(dar, resolveUserRestriction(restriction))
                        }

                        "CLEAR_USER_RESTRICTION" -> {
                            dpm.clearUserRestriction(dar, resolveUserRestriction(restriction))
                        }

                        "SET_PERMISSION_DEFAULT" -> {
                            dpm.setPermissionGrantState(
                                dar, app!!, permission!!,
                                DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT
                            )
                        }

                        "SET_PERMISSION_GRANTED" -> {
                            dpm.setPermissionGrantState(
                                dar, app!!, permission!!,
                                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                            )
                        }

                        "SET_PERMISSION_DENIED" -> {
                            dpm.setPermissionGrantState(
                                dar, app!!, permission!!,
                                DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED
                            )
                        }

                        "LOCK" -> {
                            dpm.lockNow()
                        }

                        "REBOOT" -> {
                            dpm.reboot(dar)
                        }

                        "SET_CAMERA_DISABLED" -> {
                            dpm.setCameraDisabled(dar, true)
                        }

                        "SET_CAMERA_ENABLED" -> {
                            dpm.setCameraDisabled(dar, false)
                        }

                        "SET_USB_DISABLED" -> {
                            dpm.isUsbDataSignalingEnabled = false
                        }

                        "SET_USB_ENABLED" -> {
                            dpm.isUsbDataSignalingEnabled = true
                        }

                        "SET_SCREEN_CAPTURE_DISABLED" -> {
                            dpm.setScreenCaptureDisabled(dar, true)
                        }

                        "SET_SCREEN_CAPTURE_ENABLED" -> {
                            dpm.setScreenCaptureDisabled(dar, false)
                        }

                        else -> {
                            log += "\nInvalid action"
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                val message = (e::class.qualifiedName ?: "Exception") + ": " + (e.message ?: "")
                log += "\n$message"
                setResultData(JSONObject().apply {
                    put("success", false)
                    put("error", message)
                }.toString())
            }
        } else {
            log += "\nUnauthorized"
        }
        Log.d(TAG, log)
    }

    @SuppressWarnings("NewApi")
    private fun handleGet(
        intent: Intent,
        query: String?,
        app: String?,
        permission: String?,
        restriction: String?
    ) {
        if (query.isNullOrBlank()) throw IllegalArgumentException("Missing query")

        val result = JSONObject().put("success", true).put("query", query)
        when (query.uppercase()) {
            "HIDDEN" -> {
                requirePackage(app)
                result.put("value", dpm.isApplicationHidden(dar, app))
            }
            "SUSPENDED" -> {
                requirePackage(app)
                if (Build.VERSION.SDK_INT < 24) throw IllegalArgumentException("Requires Android 7.0+")
                result.put("value", dpm.isPackageSuspended(dar, app))
            }
            "UNINSTALL_BLOCKED" -> {
                requirePackage(app)
                result.put("value", dpm.isUninstallBlocked(dar, app))
            }
            "USER_CONTROL_DISABLED" -> {
                requirePackage(app)
                if (Build.VERSION.SDK_INT < 30) throw IllegalArgumentException("Requires Android 11+")
                result.put("value", app in dpm.getUserControlDisabledPackages(dar))
            }
            "METERED_DATA_DISABLED" -> {
                requirePackage(app)
                if (Build.VERSION.SDK_INT < 28) throw IllegalArgumentException("Requires Android 9+")
                result.put("value", app in dpm.getMeteredDataDisabledPackages(dar))
            }
            "CAMERA_DISABLED" -> result.put("value", dpm.isCameraDisabled(dar))
            "SCREEN_CAPTURE_DISABLED" -> result.put("value", dpm.getScreenCaptureDisabled(dar))
            "USB_DATA_SIGNALING_ENABLED" -> {
                if (Build.VERSION.SDK_INT < 31) throw IllegalArgumentException("Requires Android 12+")
                result.put("value", dpm.isUsbDataSignalingEnabled)
            }
            "PERMISSION_STATE" -> {
                requirePackage(app)
                if (Build.VERSION.SDK_INT < 23) throw IllegalArgumentException("Requires Android 6.0+")
                if (permission.isNullOrBlank()) throw IllegalArgumentException("Missing permission")
                result.put("value", dpm.getPermissionGrantState(dar, app, permission))
            }
            "USER_RESTRICTIONS" -> {
                val restrictions = dpm.getUserRestrictions(dar)
                result.put("restrictions", JSONArray(restrictions.keys.toList()))
            }
            "USER_RESTRICTION" -> {
                val resolved = resolveUserRestriction(restriction)
                result.put("restriction", resolved)
                result.put("value", dpm.getUserRestrictions(dar).getBoolean(resolved, false))
            }
            "USER_CONTROL_DISABLED_PACKAGES" -> {
                if (Build.VERSION.SDK_INT < 30) throw IllegalArgumentException("Requires Android 11+")
                result.put("packages", JSONArray(dpm.getUserControlDisabledPackages(dar)))
            }
            "METERED_DATA_DISABLED_PACKAGES" -> {
                if (Build.VERSION.SDK_INT < 28) throw IllegalArgumentException("Requires Android 9+")
                result.put("packages", JSONArray(dpm.getMeteredDataDisabledPackages(dar)))
            }
            else -> throw IllegalArgumentException("Unknown query: $query")
        }
        setResultData(result.toString())
    }

    private fun requirePackage(app: String?) {
        if (app.isNullOrBlank()) throw IllegalArgumentException("Missing package")
    }

    private fun resolveUserRestriction(value: String?): String {
        if (value.isNullOrBlank()) {
            throw IllegalArgumentException("Missing restriction")
        }
        return try {
            val field = UserManager::class.java.getField(value)
            val resolved = field.get(null)
            if (resolved !is String) {
                throw IllegalArgumentException("UserManager.$value is not a restriction key")
            }
            resolved
        } catch (e: NoSuchFieldException) {
            if (value.indexOfAny(charArrayOf(' ', '\n', '\r', '\t')) >= 0) {
                throw IllegalArgumentException("Invalid restriction: $value")
            }
            value
        } catch (e: IllegalAccessException) {
            throw IllegalArgumentException("Unable to resolve UserManager.$value", e)
        } catch (e: SecurityException) {
            throw IllegalArgumentException("Unable to access UserManager.$value", e)
        }
    }

    companion object {
        private const val TAG = "API"
    }
}
