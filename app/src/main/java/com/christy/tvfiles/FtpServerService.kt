package com.christy.tvfiles

import android.app.Service
import android.content.Intent
import android.os.IBinder
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission

class FtpServerService : Service() {

    private var ftpServer: FtpServer? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ftpServer == null) {
            val serverFactory = FtpServerFactory()
            
            // Usuario anónimo con acceso total a la memoria interna de la TV Box
            val user = BaseUser().apply {
                name = "anonymous"
                homeDirectory = "/storage/emulated/0" 
                authorities = listOf(WritePermission()) // Permite leer, escribir y borrar archivos/APKs
            }
            serverFactory.userManager.save(user)

            val factory = serverFactory.createServer()
            ftpServer = factory
            try {
                ftpServer?.start()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        ftpServer?.stop()
        ftpServer = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}