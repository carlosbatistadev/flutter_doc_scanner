package com.shirsh.flutter_doc_scanner

import android.app.Activity
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

class FlutterDocScannerPlugin : 
    FlutterPlugin, 
    ActivityAware, 
    MethodChannel.MethodCallHandler, 
    ActivityResultListener {

    private var channel: MethodChannel? = null
    private var activity: Activity? = null
    private var activityBinding: ActivityPluginBinding? = null

    private val TAG = "FlutterDocScannerPlugin"

    // Armazena resultados pendentes. A chave é o requestCode e o valor é o callback do Flutter.
    private val pendingResults = mutableMapOf<Int, MethodChannel.Result>()

    // Códigos de requisição
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
        activityBinding?.addActivityResultListener(this)
    }

    /**
     * Chamado quando há mudança de configuração (ex.: rotação).
     * Não limpamos o pendingResults aqui para que, se o scanner já estiver em execução,
     * ainda possamos receber o resultado futuramente.
     */
    override fun onDetachedFromActivityForConfigChanges() {
        activityBinding?.removeActivityResultListener(this)
        activityBinding = null
        activity = null
    }

    /**
     * Chamado após a Activity ser recriada por mudança de configuração.
     * Reanexamos o listener para continuar recebendo o resultado pendente.
     */
    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
        activityBinding = binding
        activityBinding?.addActivityResultListener(this)
    }

    /**
     * Chamado quando a Activity é definitivamente removida (ex.: FlutterEngine destruído, navegação etc.)
     * Neste caso, não há mais como retornar resultados pendentes.
     * Se quisermos retornar erro ao Flutter, este é o momento.
     */
    override fun onDetachedFromActivity() {
        activityBinding?.removeActivityResultListener(this)

        // Se ainda houver algo pendente, retornamos erro para o Flutter.
        pendingResults.forEach { (_, resultChannel) ->
            resultChannel.error(
                "ACTIVITY_DETACHED",
                "A Activity foi encerrada antes de concluir o processo de digitalização.",
                null
            )
        }
        pendingResults.clear()

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

    private fun startDocumentScan(pageLimit: Int, requestCode: Int, result: MethodChannel.Result) {
        if (activity == null) {
            result.error(
                "ACTIVITY_NOT_AVAILABLE", 
                "Activity is null, cannot start scanner.", 
                null
            )
            return
        }

        // Se já existir um pending result com o mesmo requestCode, retornamos erro
        // para evitar conflitos (ou você pode optar por outra lógica).
        if (pendingResults.containsKey(requestCode)) {
            result.error(
                "SCAN_ALREADY_IN_PROGRESS",
                "Já existe uma digitalização em andamento para este requestCode.",
                null
            )
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
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .build()

            val scanner = GmsDocumentScanning.getClient(options)
            val task: Task<IntentSender> = scanner.getStartScanIntent(activity!!)

            task
                .addOnSuccessListener { intentSender ->
                    pendingResults[requestCode] = result
                    val intent = IntentSenderRequest.Builder(intentSender).build().intentSender
                    startIntentSenderForResult(activity!!, intent, requestCode, null, 0, 0, 0, null)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Falha ao obter Intent do scanner", e)
                    result.error(
                        "SCAN_FAILED",
                        "Falha ao obter Intent do scanner: ${e.stackTraceToString()}",
                        null
                    )
                }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao iniciar scanner", e)
            result.error(
                "SCAN_FAILED",
                "Erro ao iniciar scanner: ${e.stackTraceToString()}",
                null
            )
        }
    }

    // -------------------------
    // ActivityResultListener
    // -------------------------
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        val resultChannel = pendingResults[requestCode]
        if (resultChannel == null) {
            // Não é para nós ou perdemos o pending result (improvável se o processo ainda é o mesmo)
            Log.e(TAG, "Nenhum resultChannel encontrado para requestCode: $requestCode")
            return false
        }

        try {
            when (requestCode) {
                REQUEST_CODE_SCAN, REQUEST_CODE_SCAN_PDF -> {
                    if (resultCode == Activity.RESULT_OK) {
                        val scanningResult = GmsDocumentScanningResult.fromActivityResultIntent(data)
                        val pdf = scanningResult?.pdf
                        if (pdf != null) {
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
                                resultChannel.error(
                                    "SCAN_FAILED",
                                    "PDF URI não retornado pelo scanner",
                                    null
                                )
                            }
                        } else {
                            resultChannel.error(
                                "SCAN_FAILED",
                                "Nenhum resultado de PDF retornado",
                                null
                            )
                        }
                    } else {
                        // Se usuário cancelou ou algo assim, retornamos null para indicar sem resultado.
                        resultChannel.success(null)
                    }
                }

                REQUEST_CODE_SCAN_IMAGES, REQUEST_CODE_SCAN_URI -> {
                    if (resultCode == Activity.RESULT_OK) {
                        val scanningResult = GmsDocumentScanningResult.fromActivityResultIntent(data)
                        val pages = scanningResult?.pages
                        if (pages != null && pages.isNotEmpty()) {
                            // Mapeia cada página para sua imageUri
                            val imageUris = pages.mapNotNull { it.imageUri?.toString() }
                            if (imageUris.isNotEmpty()) {
                                resultChannel.success(
                                    mapOf(
                                        "Uris" to imageUris,
                                        "Count" to imageUris.size
                                    )
                                )
                            } else {
                                resultChannel.error(
                                    "SCAN_FAILED",
                                    "Nenhum caminho de imagem foi retornado",
                                    null
                                )
                            }
                        } else {
                            resultChannel.error(
                                "SCAN_FAILED",
                                "Nenhum resultado de imagem retornado",
                                null
                            )
                        }
                    } else {
                        resultChannel.success(null)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao processar resultado do scanner", e)
            // Se algo der errado aqui, retornamos um erro detalhado.
            resultChannel.error(
                "SCAN_PROCESSING_ERROR",
                "Erro ao processar resultado do scanner: ${e.stackTraceToString()}",
                null
            )
        } finally {
            pendingResults.remove(requestCode) // Limpa o result pendente
        }
        return true
    }
}