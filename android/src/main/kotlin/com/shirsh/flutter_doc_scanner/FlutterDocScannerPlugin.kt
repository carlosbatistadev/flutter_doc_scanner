package com.shirsh.flutter_doc_scanner

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.IntentSender
import android.os.Bundle
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

class FlutterDocScannerPlugin : FlutterPlugin, ActivityAware, MethodChannel.MethodCallHandler, ActivityResultListener {

    private var channel: MethodChannel? = null
    private var activity: Activity? = null
    private var activityBinding: ActivityPluginBinding? = null
    private val TAG = "FlutterDocScannerPlugin"

    // Armazena o resultado pendente se o app for minimizado
    private val pendingResults = mutableMapOf<Int, MethodChannel.Result>()

    // Códigos de requisição
    private val REQUEST_CODE_SCAN = 213312
    private val REQUEST_CODE_SCAN_URI = 214412
    private val REQUEST_CODE_SCAN_IMAGES = 215512
    private val REQUEST_CODE_SCAN_PDF = 216612

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        val arguments = call.arguments as? Map<*, *>
        val pageLimit = (arguments?.get("page") as? Int)?.coerceAtLeast(1) ?: 4

        when (call.method) {
            "getScanDocuments" -> startDocumentScan(pageLimit, REQUEST_CODE_SCAN, result)
            "getScannedDocumentAsImages" -> startDocumentScan(pageLimit, REQUEST_CODE_SCAN_IMAGES, result)
            "getScannedDocumentAsPdf" -> startDocumentScan(pageLimit, REQUEST_CODE_SCAN_PDF, result)
            "getScanDocumentsUri" -> startDocumentScan(pageLimit, REQUEST_CODE_SCAN_URI, result)
            else -> result.notImplemented()
        }
    }

    private fun startDocumentScan(pageLimit: Int, requestCode: Int, result: MethodChannel.Result) {
        if (activity == null) {
            result.error("ACTIVITY_NOT_AVAILABLE", "Activity is null, cannot start scanner.", null)
            return
        }

        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(pageLimit)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG, GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()

        val scanner = GmsDocumentScanning.getClient(options)
        val task: Task<IntentSender> = scanner.getStartScanIntent(activity!!)

        task.addOnSuccessListener { intentSender ->
            try {
                pendingResults[requestCode] = result // Armazena o resultado antes de iniciar o scanner
                val intent = IntentSenderRequest.Builder(intentSender).build().intentSender
                startIntentSenderForResult(activity!!, intent, requestCode, null, 0, 0, 0, null)
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao iniciar scanner", e)
                result.error("SCAN_FAILED", "Erro ao iniciar scanner: ${e.localizedMessage}", null)
            }
        }.addOnFailureListener { e ->
            Log.e(TAG, "Falha ao obter Intent do scanner", e)
            result.error("SCAN_FAILED", "Falha ao obter Intent do scanner: ${e.localizedMessage}", null)
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
                    scanningResult?.getPdf()?.let { pdf ->
                        val pdfUri = pdf.getUri()
                        val pageCount = pdf.getPageCount()
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
                    scanningResult?.getPages()?.let { pages ->
                        val imageUris = pages.mapNotNull { it.getUri()?.toString() }
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

        pendingResults.remove(requestCode) // Remove o resultado pendente após ser usado
        return true
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(binding.binaryMessenger, "flutter_doc_scanner")
        channel?.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel?.setMethodCallHandler(null)
        channel = null
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        activityBinding = binding
        activityBinding?.addActivityResultListener(this)
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
