package com.lilian.cifra

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.UUID

data class CapturedTransaction(
    val id: String,
    val source: String,
    val description: String,
    val amount: Double,
    val timestamp: Long,
    val direction: String = "OUT",
    val kind: String = "ACCOUNT",
    val installments: Int = 1,
    val countsInLimit: Boolean? = null
)

object TransactionStore {
    private const val PREFS = "cifra_local_transactions"
    private const val KEY = "transactions"

    fun load(context: Context): List<CapturedTransaction> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                CapturedTransaction(
                    id = item.getString("id"),
                    source = item.getString("source"),
                    description = item.getString("description"),
                    amount = item.getDouble("amount"),
                    timestamp = item.getLong("timestamp"),
                    direction = item.optString("direction", "OUT"),
                    kind = item.optString("kind", if (item.optString("direction") == "IN") "ACCOUNT" else if (item.optString("description").contains("compra", true)) "CREDIT" else "ACCOUNT"),
                    installments = item.optInt("installments", 1).coerceAtLeast(1),
                    countsInLimit = if (item.has("countsInLimit")) item.optBoolean("countsInLimit") else null
                )
            }.sortedByDescending { it.timestamp }
        }.getOrDefault(emptyList())
    }

    fun add(context: Context, transaction: CapturedTransaction) {
        val current = load(context).toMutableList()
        val duplicate = current.any {
            it.source == transaction.source && it.amount == transaction.amount &&
                kotlin.math.abs(it.timestamp - transaction.timestamp) < 60_000
        }
        if (duplicate) return
        current.add(0, transaction)
        val array = JSONArray()
        current.take(500).forEach {
            array.put(JSONObject().apply {
                put("id", it.id); put("source", it.source); put("description", it.description)
                put("amount", it.amount); put("timestamp", it.timestamp); put("direction", it.direction); put("kind", it.kind)
                put("installments", it.installments)
                if (it.countsInLimit != null) put("countsInLimit", it.countsInLimit)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }

    fun delete(context: Context, id: String) {
        save(context, load(context).filterNot { it.id == id })
    }

    fun update(context: Context, transaction: CapturedTransaction) {
        save(context, load(context).map { if (it.id == transaction.id) transaction else it })
    }

    private fun save(context: Context, transactions: List<CapturedTransaction>) {
        val array = JSONArray()
        transactions.take(500).forEach {
            array.put(JSONObject().apply {
                put("id", it.id); put("source", it.source); put("description", it.description)
                put("amount", it.amount); put("timestamp", it.timestamp); put("direction", it.direction); put("kind", it.kind)
                put("installments", it.installments)
                if (it.countsInLimit != null) put("countsInLimit", it.countsInLimit)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }
}

const val ACTION_TRANSACTIONS_CHANGED = "com.lilian.cifra.TRANSACTIONS_CHANGED"

data class AccountBalances(val mercadoPago: Double = 128.47, val cash: Double = 55.10)

object BalanceStore {
    private const val PREFS = "cifra_balances"
    fun load(context: Context): AccountBalances {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return AccountBalances(
            mercadoPago = java.lang.Double.longBitsToDouble(prefs.getLong("mercado_pago", java.lang.Double.doubleToLongBits(128.47))),
            cash = java.lang.Double.longBitsToDouble(prefs.getLong("cash", java.lang.Double.doubleToLongBits(55.10)))
        )
    }
    fun save(context: Context, balances: AccountBalances) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("mercado_pago", java.lang.Double.doubleToRawLongBits(balances.mercadoPago))
            .putLong("cash", java.lang.Double.doubleToRawLongBits(balances.cash)).apply()
    }
}

class CifraNotificationListener : NotificationListenerService() {
    override fun onCreate() {
        super.onCreate()
        keepCaptureAlive()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        keepCaptureAlive()
        activeNotifications?.forEach { capture(it) }
    }

    private fun keepCaptureAlive() {
        val channelId = "cifra_capture_status"
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "Captura automática", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Mantém ativa a identificação de compras e Pix"
                    setShowBadge(false)
                }
            )
        }
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val status = Notification.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Cifra ativo")
            .setContentText("Identificando compras e Pix automaticamente")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1706, status, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1706, status)
        }
    }


    override fun onNotificationPosted(sbn: StatusBarNotification) = capture(sbn)

    private fun capture(sbn: StatusBarNotification) {
        val source = when {
            sbn.packageName.contains("nubank", ignoreCase = true) || sbn.packageName == "com.nu.production" -> "Nubank"
            sbn.packageName.contains("mercadopago", ignoreCase = true) -> "Mercado Pago"
            else -> return
        }
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = sequenceOf(
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString(" ")
        ).filterNotNull().joinToString(" ")
        val completeText = "$title $body"
        val normalized = Normalizer.normalize(completeText.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        val incomingWords = listOf("pix recebido", "voce recebeu", "recebimento", "transferencia recebida", "dinheiro recebido", "valor recebido", "deposito recebido")
        val outgoingWords = listOf("compra", "aprovada", "aprovado", "pagamento", "pagou", "debitamos", "debitado", "pix enviado", "pix realizado", "transferencia enviada", "transferiu", "debito", "retirada", "saque")
        val direction = when {
            incomingWords.any { normalized.contains(it) } -> "IN"
            outgoingWords.any { normalized.contains(it) } -> "OUT"
            source == "Nubank" -> "OUT"
            else -> return
        }
        val kind = if (source == "Nubank" && direction == "OUT" && listOf("compra", "cartao", "credito").any { normalized.contains(it) }) "CREDIT" else "ACCOUNT"
        val match = Regex("R\\$[\\s\\u00A0]*([0-9.]+(?:,[0-9]{2})?)").find(completeText) ?: return
        val amount = match.groupValues[1].replace(".", "").replace(",", ".").toDoubleOrNull() ?: return
        if (amount <= 0) return
        val nubankMerchant = Regex("APROVADA em (.+?) para o cart", RegexOption.IGNORE_CASE).find(body)?.groupValues?.getOrNull(1)?.trim()
        val description = nubankMerchant ?: title.ifBlank { body.substringBefore("R$").trim() }.ifBlank { "Compra" }
        val installments = sequenceOf(
            Regex("(?:em\\s+)?(\\d{1,2})\\s*x", RegexOption.IGNORE_CASE).find(normalized)?.groupValues?.getOrNull(1),
            Regex("(\\d{1,2})\\s+parcelas?", RegexOption.IGNORE_CASE).find(normalized)?.groupValues?.getOrNull(1)
        ).filterNotNull().firstOrNull()?.toIntOrNull()?.coerceIn(1, 48) ?: 1
        TransactionStore.add(
            this,
            CapturedTransaction(UUID.randomUUID().toString(), source, description, amount, sbn.postTime, direction, kind, installments)
        )
        sendBroadcast(android.content.Intent(ACTION_TRANSACTIONS_CHANGED).setPackage(packageName))
    }
}
