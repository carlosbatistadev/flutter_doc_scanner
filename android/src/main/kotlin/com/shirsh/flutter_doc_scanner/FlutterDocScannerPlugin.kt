package com.shirsh.flutter_doc_scanner

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

class FlutterDocScannerPlugin : FlutterPlugin, ActivityAware, MethodChannel.MethodCallHandler {

    private var channel: MethodChannel? = null
    private var activity: Activity? = null
    private var activityBinding: ActivityPluginBinding? = null

    private val TAG = "FlutterDocScannerPlugin"

    // Variáveis para armazenar o callback pendente e o tipo de operação (equivalente ao requestCode)
    private var pendingScanType: Int? = null
    private var pendingMethodResult: MethodChannel.Result? = null

    // Launcher para iniciar o IntentSender com a nova API de Activity Result
    private var scanLauncher: ActivityResultLauncher<IntentSenderRequest>? = null

    // Tipos de operação (antigos requestCodes)
    private val REQUEST_CODE_SCAN = 213312
    private val REQUEST_CODE_SCAN_URI = 214412
    private val REQUEST_CODE_SCAN_IMAGES = 215512
    private val REQUEST_CODE_SCAN_PDF = 216612

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(binding.binaryMessenger, "flutter_doc_scanner")
        channel?.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel?.setMethodCallHandler(null)
        channel = null
    }

    // -------------------------
    // ActivityAware Overrides
    // -------------------------
    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        activityBinding = binding

        // Registra o callback incondicionalmente usando registerForActivityResult
        if (activity is ComponentActivity) {
            scanLauncher = (activity as ComponentActivity).registerForActivityResult(
                ActivityResultContracts.StartIntentSenderForResult()
            ) { result: ActivityResult ->
                handleScanResult(result)
            }
        } else {
            Log.e(TAG, "Activity não é ComponentActivity. Considere usar a API legacy.")
        }
    }

    /**
     * Chamado em mudança de configuração (ex.: rotação).
     * Não limpamos os pendentes para que possamos restaurar a operação em andamento.
     */
    override fun onDetachedFromActivityForConfigChanges() {
        activityBinding = null
        activity = null
    }

    /**
     * Após a Activity ser recriada, reanexamos o launcher para continuar recebendo o resultado.
     */
    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
        activityBinding = binding
        if (activity is ComponentActivity) {
            scanLauncher = (activity as ComponentActivity).registerForActivityResult(
                ActivityResultContracts.StartIntentSenderForResult()
            ) { result: ActivityResult ->
                handleScanResult(result)
            }
        }
    }

    /**
     * Quando a Activity é definitivamente removida, retornamos erro para o Flutter se houver operação pendente.
     */
    override fun onDetachedFromActivity() {
        pendingMethodResult?.error(
            "ACTIVITY_DETACHED",
            "A Activity foi encerrada antes de concluir o processo de digitalização.",
            null
        )
        pendingMethodResult = null
        pendingScanType = null
        activityBinding = null
        activity = null
    }

    // -------------------------
    // MethodCallHandler
    // -------------------------
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

    /**
     * Inicia a digitalização do documento, armazenando o callback e o tipo pendente,
     * e utilizando o ActivityResultLauncher para disparar a atividade de scanner.
     */
    private fun startDocumentScan(pageLimit: Int, scanType: Int, result: MethodChannel.Result) {
        if (activity == null) {
            result.error("ACTIVITY_NOT_AVAILABLE", "Activity é nula, não é possível iniciar o scanner.", null)
            return
        }

        if (pendingMethodResult != null) {
            result.error("SCAN_ALREADY_IN_PROGRESS", "Já existe uma digitalização em andamento.", null)
            return
        }

        try {
            val options = GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(true)
                .setPageLimit(pageLimit)
                .setResultFormats(
                    GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                    GmsDocumentScannerOptions.RESULT_FORMAT_PDF
                )
                .setScannerMode(GmsDocumentScannerOptions.CAPTURE_MODE_MANUAL)
                .build()

            val scanner = GmsDocumentScanning.getClient(options)
            val task: Task<IntentSender> = scanner.getStartScanIntent(activity!!)

            task.addOnSuccessListener { intentSender ->
                // Armazena as informações pendentes para uso na callback
                pendingScanType = scanType
                pendingMethodResult = result

                val intentSenderRequest = IntentSenderRequest.Builder(intentSender).build()
                scanLauncher?.launch(intentSenderRequest) ?: run {
                    result.error("SCAN_FAILED", "ActivityResultLauncher não disponível", null)
                }
            }.addOnFailureListener { e ->
                Log.e(TAG, "Falha ao obter Intent do scanner", e)
                result.error("SCAN_FAILED", "Falha ao obter Intent do scanner: ${e.stackTraceToString()}", null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao iniciar scanner", e)
            result.error("SCAN_FAILED", "Erro ao iniciar scanner: ${e.stackTraceToString()}", null)
        }
    }

    /**
     * Processa o resultado da digitalização, diferenciando entre PDF e imagens
     * conforme o tipo de operação pendente.
     */
    private fun handleScanResult(result: ActivityResult) {
        val currentResult = pendingMethodResult
        val scanType = pendingScanType

        // Limpa os pendentes, para evitar reenvio
        pendingMethodResult = null
        pendingScanType = null

        if (currentResult == null) {
            Log.e(TAG, "Nenhum callback pendente para processar o resultado.")
            return
        }

        try {
            when (scanType) {
                REQUEST_CODE_SCAN, REQUEST_CODE_SCAN_PDF -> {
                    if (result.resultCode == Activity.RESULT_OK) {
                        val scanningResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
                        val pdf = scanningResult?.pdf
                        if (pdf != null) {
                            val pdfUri = pdf.uri
                            val pageCount = pdf.pageCount
                            if (pdfUri != null) {
                                currentResult.success(
                                    mapOf(
                                        "pdfUri" to pdfUri.toString(),
                                        "pageCount" to pageCount
                                    )
                                )
                            } else {
                                currentResult.error("SCAN_FAILED", "PDF URI não retornado pelo scanner", null)
                            }
                        } else {
                            currentResult.error("SCAN_FAILED", "Nenhum resultado de PDF retornado", null)
                        }
                    } else {
                        currentResult.success(null)
                    }
                }
                REQUEST_CODE_SCAN_IMAGES, REQUEST_CODE_SCAN_URI -> {
                    if (result.resultCode == Activity.RESULT_OK) {
                        val scanningResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
                        val pages = scanningResult?.pages
                        if (pages != null && pages.isNotEmpty()) {
                            val imageUris = pages.mapNotNull { it.imageUri?.toString() }
                            if (imageUris.isNotEmpty()) {
                                currentResult.success(
                                    mapOf(
                                        "Uris" to imageUris,
                                        "Count" to imageUris.size
                                    )
                                )
                            } else {
                                currentResult.error("SCAN_FAILED", "Nenhum caminho de imagem foi retornado", null)
                            }
                        } else {
                            currentResult.error("SCAN_FAILED", "Nenhum resultado de imagem retornado", null)
                        }
                    } else {
                        currentResult.success(null)
                    }
                }
                else -> {
                    currentResult.error("SCAN_FAILED", "Tipo de operação desconhecido", null)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao processar resultado do scanner", e)
            currentResult.error("SCAN_PROCESSING_ERROR", "Erro ao processar resultado do scanner: ${e.stackTraceToString()}", null)
        }
    }
}
