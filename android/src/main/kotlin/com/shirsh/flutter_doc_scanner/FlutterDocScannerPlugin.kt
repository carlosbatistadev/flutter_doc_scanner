package com.shirsh.flutter_doc_scanner

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.NonNull
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler

class FlutterDocScannerPlugin : FlutterPlugin, ActivityAware, MethodCallHandler {
    private var channel: MethodChannel? = null
    private var activity: Activity? = null
    private var scannerLauncher: ActivityResultLauncher<IntentSenderRequest>? = null
    private val TAG = "FlutterDocScannerPlugin"

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "flutter_doc_scanner")
        channel?.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        channel?.setMethodCallHandler(null)
        channel = null
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        setupActivityResultLauncher()
    }

    override fun onDetachedFromActivity() {
        activity = null
        scannerLauncher = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity()
    }

    private fun setupActivityResultLauncher() {
        activity?.let { activity ->
            scannerLauncher = activity.registerForActivityResult(
                ActivityResultContracts.StartIntentSenderForResult()
            ) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    val scanningResult = GmsDocumentScanningResult.fromIntent(result.data)
                    scanningResult?.let { processScanningResult(it) }
                } else {
                    Log.e(TAG, "Scanning canceled or failed with resultCode: ${result.resultCode}")
                }
            }
        }
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: MethodChannel.Result) {
        when (call.method) {
            "getPlatformVersion" -> {
                result.success("Android ${android.os.Build.VERSION.RELEASE}")
            }
            "getScanDocuments" -> {
                val pageLimit = (call.argument<Int>("page") ?: 4).coerceAtLeast(1)
                startDocumentScan(pageLimit, result)
            }
            else -> {
                result.notImplemented()
            }
        }
    }

    private fun startDocumentScan(pageLimit: Int, result: MethodChannel.Result) {
        activity?.let { activity ->
            val options = GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(true)
                .setPageLimit(pageLimit)
                .setResultFormats(
                    GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                    GmsDocumentScannerOptions.RESULT_FORMAT_PDF
                )
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .build()

            val scanner = GmsDocumentScanning.getClient(activity, options)
            scanner.getStartScanIntent()
                .addOnSuccessListener { intentSender ->
                    try {
                        val intentSenderRequest = IntentSenderRequest.Builder(intentSender).build()
                        scannerLauncher?.launch(intentSenderRequest)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start document scan", e)
                        result.error("SCAN_FAILED", "Failed to start document scan", e.localizedMessage)
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to get scan intent", e)
                    result.error("SCAN_FAILED", "Failed to get scan intent", e.localizedMessage)
                }
        } ?: run {
            result.error("ACTIVITY_NOT_AVAILABLE", "Activity is not available", null)
        }
    }

    private fun processScanningResult(scanningResult: GmsDocumentScanningResult) {
        val pages = scanningResult.pages
        val pdf = scanningResult.pdf

        val pageUris = pages.mapNotNull { it.imageUri?.toString() }
        val pdfUri = pdf?.uri?.toString()
        val pageCount = pdf?.pageCount ?: 0

        val resultData = mutableMapOf<String, Any>()
        if (pageUris.isNotEmpty()) {
            resultData["imageUris"] = pageUris
        }
        pdfUri?.let {
            resultData["pdfUri"] = it
            resultData["pageCount"] = pageCount
        }

        if (resultData.isNotEmpty()) {
            channel?.invokeMethod("onDocumentScanned", resultData)
        } else {
            Log.e(TAG, "No valid scanning results found")
        }
    }
}
