package com.shirsh.flutter_doc_scanner

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.IntentSender
import android.util.Log
import androidx.activity.result.IntentSenderRequest
import androidx.core.app.ActivityCompat.startIntentSenderForResult
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry.ActivityResultListener

class FlutterDocScannerPlugin : MethodChannel.MethodCallHandler, ActivityResultListener,
    FlutterPlugin, ActivityAware {

    private var channel: MethodChannel? = null
    private var pluginBinding: FlutterPlugin.FlutterPluginBinding? = null
    private var activityBinding: ActivityPluginBinding? = null
    private var activity: Activity? = null
    private val CHANNEL = "flutter_doc_scanner"
    private val TAG = "FlutterDocScannerPlugin"

    // Códigos de requisição para diferentes tipos de digitalização
    private val REQUEST_CODE_SCAN = 213312
    private val REQUEST_CODE_SCAN_IMAGES = 215512
    private val REQUEST_CODE_SCAN_PDF = 216612
    private val REQUEST_CODE_SCAN_URI = 214412

    // Mapeia requestCodes para os `Result` do Flutter
    private val pendingResults = mutableMapOf<Int, MethodChannel.Result>()

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        val arguments = call.arguments as? Map<*, *>
        val pageLimit = (arguments?.get("page") as? Int)?.coerceAtLeast(1) ?: 4

        when (call.method) {
            "getScanDocuments" -> startDocumentScan(pageLimit, result, REQUEST_CODE_SCAN)
            "getScannedDocumentAsImages" -> startDocumentScan(pageLimit, result, REQUEST_CODE_SCAN_IMAGES)
            "getScannedDocumentAsPdf" -> startDocumentScan(pageLimit, result, REQUEST_CODE_SCAN_PDF)
            "getScanDocumentsUri" -> startDocumentScan(pageLimit, result, REQUEST_CODE_SCAN_URI)
            else -> result.notImplemented()
        }
    }

    private fun startDocumentScan(page: Int, result: MethodChannel.Result, requestCode: Int) {
        if (activity == null) {
            result.error("ACTIVITY_NOT_AVAILABLE", "Activity is null, cannot start scanner.", null)
            return
        }

        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(page)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG, GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()

        val scanner = GmsDocumentScanning.getClient(options)
        val task: Task<IntentSender> = scanner.getStartScanIntent(activity!!)

        task.addOnSuccessListener { intentSender ->
            try {
                pendingResults[requestCode] = result // Armazena o result antes de iniciar o scanner
                val intent = IntentSenderRequest.Builder(intentSender).build().intentSender
                startIntentSenderForResult(activity!!, intent, requestCode, null, 0, 0, 0, null)
            } catch (e: Exception) {
                result.error("SCAN_FAILED", "Erro ao iniciar scanner: ${e.message}", null)
                pendingResults.remove(requestCode)
            }
        }.addOnFailureListener { e ->
            result.error("SCAN_FAILED", "Falha ao iniciar scanner: ${e.message}", null)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        val resultChannel = pendingResults[requestCode]
        if (resultChannel == null) {
            Log.e(TAG, "Erro: Nenhum resultChannel encontrado para requestCode: $requestCode")
            return false
        }

        when (requestCode) {
            REQUEST_CODE_SCAN, REQUEST_CODE_SCAN_PDF -> {
                if (resultCode == Activity.RESULT_OK) {
                    val scanningResult = GmsDocumentScanningResult.fromActivityResultIntent(data)
                    scanningResult?.pdf?.let { pdf ->
                        val pdfUri = pdf.uri
                        val pageCount = pdf.pageCount
                        if (pdfUri != null) {
                            resultChannel.success(
                                mapOf(
                                    "pdfUri" to pdfUri.toString(),
                                    "pageCount" to pageCount
                                )
                            )
                        } else {
                            resultChannel.error("SCAN_FAILED", "PDF URI não retornado pelo scanner", null)
                        }
                    } ?: resultChannel.error("SCAN_FAILED", "Nenhum resultado de PDF retornado", null)
                } else {
                    resultChannel.success(null)
                }
            }

            REQUEST_CODE_SCAN_IMAGES, REQUEST_CODE_SCAN_URI -> {
                if (resultCode == Activity.RESULT_OK) {
                    val scanningResult = GmsDocumentScanningResult.fromActivityResultIntent(data)
                    scanningResult?.pages?.let { pages ->
                        val imageUris = pages.mapNotNull { it.uri?.toString() }
                        if (imageUris.isNotEmpty()) {
                            resultChannel.success(
                                mapOf(
                                    "Uris" to imageUris,
                                    "Count" to imageUris.size
                                )
                            )
                        } else {
                            resultChannel.error("SCAN_FAILED", "Nenhum caminho de imagem foi retornado", null)
                        }
                    } ?: resultChannel.error("SCAN_FAILED", "Nenhum resultado de imagem retornado", null)
                } else {
                    resultChannel.success(null)
                }
            }
        }

        pendingResults.remove(requestCode) // Limpa o resultado após a entrega
        return true
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        pluginBinding = binding
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        pluginBinding = null
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activityBinding = binding
        activity = binding.activity
        activityBinding?.addActivityResultListener(this)
        channel = MethodChannel(pluginBinding!!.binaryMessenger, CHANNEL)
        channel!!.setMethodCallHandler(this)
    }

    override fun onDetachedFromActivity() {
        activityBinding?.removeActivityResultListener(this)
        activityBinding = null
        activity = null
    }

    override fun onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity()
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }
}
