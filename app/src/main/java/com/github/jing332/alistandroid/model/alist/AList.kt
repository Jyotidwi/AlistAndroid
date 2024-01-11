package com.github.jing332.alistandroid.model.alist

import android.annotation.SuppressLint
import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.github.jing332.alistandroid.R
import com.github.jing332.alistandroid.app
import com.github.jing332.alistandroid.constant.LogLevel
import com.github.jing332.alistandroid.data.appDb
import com.github.jing332.alistandroid.data.entities.ServerLog
import com.github.jing332.alistandroid.data.entities.ServerLog.Companion.evalLog
import com.github.jing332.alistandroid.service.AlistService
import com.github.jing332.alistandroid.util.FileUtils.readAllText
import com.github.jing332.alistandroid.util.StringUtils.removeAnsiCodes
import com.github.jing332.alistandroid.util.ToastUtils.longToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

object AList {
    const val ACTION_STATUS_CHANGED =
        "com.github.jing332.alistandroid.AList.ACTION_STATUS_CHANGED"

    const val TYPE_HTTP = "http"
    const val TYPE_HTTPS = "https"
    const val TYPE_UNIX = "unix"

    private val execPath by lazy {
        context.applicationInfo.nativeLibraryDir + File.separator + "libalist.so"
    }

    val context = app

    val dataPath: String
        get() = context.getExternalFilesDir("data")?.absolutePath!!

    val configPath: String
        get() = "$dataPath${File.separator}config.json"

    /**
     * 是否有服务正在运行
     */
    val hasRunning: Boolean
        get() = false

    fun init() {
//        Alistlib.setConfigData(dataPath)
//            Alistlib.setConfigDebug(BuildConfig.DEBUG)
//        Alistlib.setConfigLogStd(true)

//        Log.i(AlistService.TAG, "level=${level}, msg=$msg")
//        appDb.serverLogDao.insert(ServerLog(level = level.toInt(), message = msg))
    }

    fun setAdminPassword(pwd: String) {
        if (!hasRunning) {
            init()
        }

        val log = execWithParams(
            redirect = true,
            params = arrayOf("admin", "set", pwd, "--data", dataPath)
        ).inputStream.readAllText()
        appDb.serverLogDao.insert(ServerLog(level = LogLevel.INFO, message = log.removeAnsiCodes()))
    }


    fun shutdown(timeout: Long = 5000L) {
        runCatching {
            mProcess?.destroy()
        }.onFailure {
            context.longToast(R.string.server_shutdown_failed, it.toString())
        }
    }

    private var mProcess: Process? = null

    private suspend fun errorLogWatcher(onNewLine: (String) -> Unit) {
        mProcess?.apply {
            errorStream.bufferedReader().use {
                while (coroutineContext.isActive) {
                    val line = it.readLine() ?: break
                    Log.d(AlistService.TAG, "Process errorStream: $line")
                    onNewLine(line)
                }
            }
        }
    }

    private suspend fun logWatcher(onNewLine: (String) -> Unit) {
        mProcess?.apply {
            inputStream.bufferedReader().use {
                while (coroutineContext.isActive) {
                    val line = it.readLine() ?: break
                    Log.d(AlistService.TAG, "Process inputStream: $line")
                    onNewLine(line)
                }
            }
        }
    }

    private val mScope = CoroutineScope(Dispatchers.IO + Job())
    private fun initOutput() {
        val dao = appDb.serverLogDao
        mScope.launch {
            runCatching {
                logWatcher { msg ->
                    msg.removeAnsiCodes().evalLog()?.let {
                        dao.insert(
                            ServerLog(
                                level = it.level,
                                message = it.message
                            )
                        )
                        return@logWatcher
                    }

                    dao.insert(
                        ServerLog(
                            level = if (msg.startsWith("fail")) LogLevel.ERROR else LogLevel.INFO,
                            message = msg
                        )
                    )

                }
            }.onFailure {
                it.printStackTrace()
            }
        }
        mScope.launch {
            runCatching {
                errorLogWatcher { msg ->
                    val log = msg.removeAnsiCodes().evalLog() ?: return@errorLogWatcher
                    dao.insert(
                        ServerLog(
                            level = log.level,
                            message = log.message,
//                            description = log.time + "\n" + log.code
                        )
                    )
                }
            }.onFailure {
                it.printStackTrace()
            }
        }
    }


    @SuppressLint("SdCardPath")
    fun startup(
        dataFolder: String = context.getExternalFilesDir("data")?.absolutePath
            ?: "/data/data/${context.packageName}/files/data"
    ): Int {
        appDb.serverLogDao.deleteAll()
        mProcess =
            execWithParams(params = arrayOf("server", "--data", dataFolder))
        initOutput()

        return mProcess!!.waitFor()
    }


    private fun execWithParams(
        redirect: Boolean = false,
        vararg params: String
    ): Process {
        val cmdline = arrayOfNulls<String>(params.size + 1)
        cmdline[0] = execPath
        System.arraycopy(params, 0, cmdline, 1, params.size)
        return ProcessBuilder(*cmdline).redirectErrorStream(redirect).start()
            ?: throw IOException("Process is null!")
    }
}