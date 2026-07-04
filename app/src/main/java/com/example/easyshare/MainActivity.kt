package com.example.easyshare

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.cio.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SimpleShareApp(cacheDir = cacheDir, filesDir = filesDir)
                }
            }
        }
    }
}

@Composable
fun SimpleShareApp(cacheDir: File, filesDir: File) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var statusText by remember { mutableStateOf("اختر العملية لبدء النقل بسرعة") }
    
    val server = remember { FileReceiverServer(filesDir) }
    val client = remember { FileSenderClient() }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { selectedUri ->
            scope.launch(Dispatchers.IO) {
                statusText = "جاري تحضير وإرسال الملف..."
                val fileToSend = uriToFile(context, selectedUri, cacheDir)
                if (fileToSend != null) {
                    val receiverIp = "192.168.43.1" 
                    val result = client.sendFile(receiverIp, fileToSend)
                    statusText = result
                } else {
                    statusText = "فشل في قراءة الملف المحدد!"
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = statusText, 
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(bottom = 40.dp)
        )

        Button(
            onClick = {
                statusText = "الرجاء تشغيل (Hotspot) وإعطاء كلمة المرور لرفيقك..."
                context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                server.startServer { fileName ->
                    statusText = "تم استلام الملف بنجاح وحفظه باسم:\n$fileName"
                }
            },
            modifier = Modifier.fillMaxWidth().height(65.dp)
        ) {
            Text("اضغط هنا للاستقبال (افتح نقطة الاتصال)", style = MaterialTheme.typography.titleMedium)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                statusText = "اتصل بشبكة صديقك, ثم اختر الملف لإرساله..."
                context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                filePickerLauncher.launch("*/*")
            },
            modifier = Modifier.fillMaxWidth().height(65.dp)
        ) {
            Text("اضغط هنا للإرسال (افتح الواي فاي)", style = MaterialTheme.typography.titleMedium)
        }
    }
}

class FileReceiverServer(private val outputDirectory: File) {
    private var server: CIOApplicationEngine? = null

    fun startServer(onFileReceived: (String) -> Unit) {
        if (server != null) return 
        server = embeddedServer(CIO, port = 8080) {
            routing {
                post("/upload") {
                    val multipart = call.receiveMultipart()
                    var fileName = "received_file"
                    
                    multipart.forEachPart { part ->
                        if (part is PartData.FileItem) {
                            fileName = part.originalFileName ?: "file_${System.currentTimeMillis()}"
                            val file = File(outputDirectory, fileName)
                            
                            withContext(Dispatchers.IO) {
                                part.streamProvider().use { input ->
                                    file.outputStream().use { output -> input.copyTo(output) }
                                }
                            }
                        }
                        part.dispose()
                    }
                    call.respondText("تم الاستلام!")
                    onFileReceived(fileName)
                }
            }
        }.start(wait = false)
    }
}

class FileSenderClient {
    private val client = HttpClient(CIO)

    suspend fun sendFile(receiverIp: String, file: File): String {
        return try {
            client.submitFormWithBinaryData(
                url = "http://$receiverIp:8080/upload",
                formData = formData {
                    append("file", file.readBytes(), Headers.build {
                        append(HttpHeaders.ContentDisposition, "filename=\"${file.name}\"")
                    })
                }
            ) {
                timeout { requestTimeoutMillis = 600000 } 
            }
            "تم إرسال الملف بنجاح!"
        } catch (e: Exception) {
            "خطأ في الإرسال: ${e.localizedMessage}"
        }
    }
}

fun uriToFile(context: Context, uri: Uri, cacheDir: File): File? {
    return try {
        var name = "temp_file"
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) name = it.getString(nameIndex)
            }
        }
        val file = File(cacheDir, name)
        val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
        val outputStream = FileOutputStream(file)
        inputStream?.use { input ->
            outputStream.use { output -> input.copyTo(output) }
        }
        file
    } catch (e: Exception) {
        null
    }
}
