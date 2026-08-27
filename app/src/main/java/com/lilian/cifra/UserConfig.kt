package com.lilian.cifra

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray

data class UserConfig(
    val configured: Boolean = false,
    val name: String = "",
    val monthlyIncome: Double = 0.0,
    val fixedExpenses: Double = 0.0,
    val reserveGoal: Double = 0.0,
    val cardBudget: Double = 1000.0,
    val knownInvoice: Double = 0.0,
    val cardName: String = "Cartão principal",
    val bankAccountName: String = "Conta principal",
    val walletName: String = "Carteira digital",
    val cashName: String = "Dinheiro",
    val legacyPreset: Boolean = false
)

object UserConfigStore {
    private const val PREFS = "cifra_user_config"
    private const val KEY = "profile"

    fun loadOrMigrate(context: Context, hasExistingData: Boolean): UserConfig {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null)
        if (raw != null) {
            val decoded = decode(raw)
            if (decoded.legacyPreset && kotlin.math.abs(decoded.fixedExpenses - 1057.39) < 0.01) {
                return decoded.copy(fixedExpenses = 431.0).also { save(context, it) }
            }
            return decoded
        }
        if (!hasExistingData) return UserConfig()
        val migrated = UserConfig(
            configured = true,
            name = "Lilian",
            monthlyIncome = 2500.0,
            fixedExpenses = 431.0,
            reserveGoal = 500.0,
            cardBudget = 1000.0,
            knownInvoice = 626.39,
            cardName = "Nubank",
            bankAccountName = "Conta Nubank",
            walletName = "Mercado Pago",
            cashName = "Dinheiro",
            legacyPreset = true
        )
        save(context, migrated)
        return migrated
    }

    fun save(context: Context, config: UserConfig) {
        val json = JSONObject().apply {
            put("configured", config.configured)
            put("name", config.name)
            put("monthlyIncome", config.monthlyIncome)
            put("fixedExpenses", config.fixedExpenses)
            put("reserveGoal", config.reserveGoal)
            put("cardBudget", config.cardBudget)
            put("knownInvoice", config.knownInvoice)
            put("cardName", config.cardName)
            put("bankAccountName", config.bankAccountName)
            put("walletName", config.walletName)
            put("cashName", config.cashName)
            put("legacyPreset", config.legacyPreset)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, json.toString()).apply()
    }

    private fun decode(raw: String): UserConfig = runCatching {
        val json = JSONObject(raw)
        UserConfig(
            configured = json.optBoolean("configured"),
            name = json.optString("name"),
            monthlyIncome = json.optDouble("monthlyIncome"),
            fixedExpenses = json.optDouble("fixedExpenses"),
            reserveGoal = json.optDouble("reserveGoal"),
            cardBudget = json.optDouble("cardBudget", 1000.0),
            knownInvoice = json.optDouble("knownInvoice"),
            cardName = json.optString("cardName", "Cartão principal"),
            bankAccountName = json.optString("bankAccountName", "Conta Nubank"),
            walletName = json.optString("walletName", "Carteira digital"),
            cashName = json.optString("cashName", "Dinheiro"),
            legacyPreset = json.optBoolean("legacyPreset")
        )
    }.getOrDefault(UserConfig())
}

object MonthlyChecklistStore {
    private const val PREFS = "cifra_monthly_checklist"

    fun load(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("completed", emptySet())?.toSet() ?: emptySet()

    fun toggle(context: Context, key: String): Set<String> {
        val updated = load(context).toMutableSet().apply { if (!add(key)) remove(key) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet("completed", updated).apply()
        return updated
    }
}

data class PurchaseOverride(val title: String, val amount: Double, val installments: Int, val countsInLimit: Boolean)

object PurchaseOverrideStore {
    private const val PREFS = "cifra_purchase_overrides"
    private const val KEY = "items"

    fun load(context: Context): Map<String, PurchaseOverride> = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val array = JSONArray(raw)
        (0 until array.length()).associate { index ->
            val item = array.getJSONObject(index)
            item.getString("id") to PurchaseOverride(item.getString("title"), item.getDouble("amount"), item.optInt("installments", 1).coerceAtLeast(1), item.optBoolean("countsInLimit", true))
        }
    }.getOrDefault(emptyMap())

    fun save(context: Context, id: String, override: PurchaseOverride): Map<String, PurchaseOverride> {
        val updated = load(context).toMutableMap().apply { put(id, override) }
        val array = JSONArray()
        updated.forEach { (itemId, item) -> array.put(JSONObject().apply { put("id", itemId); put("title", item.title); put("amount", item.amount); put("installments", item.installments); put("countsInLimit", item.countsInLimit) }) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
        return updated
    }
}

data class RecurringItem(val id: String, val name: String, val amount: Double, val income: Boolean, val dueDay: Int)

object RecurringItemStore {
    private const val PREFS = "cifra_recurring_items"
    private const val KEY = "items"

    fun load(context: Context, legacyPreset: Boolean): List<RecurringItem> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null)
        if (raw == null) {
            val defaults = if (legacyPreset) listOf(
                RecurringItem("salario_senac", "Salário Senac", 1200.0, true, 1),
                RecurringItem("salario_awl", "Salário AWL", 1300.0, true, 5),
                RecurringItem("dentista", "Dentista", 89.0, false, 5),
                RecurringItem("unhas", "Pacote 6 unhas", 175.0, false, 5),
                RecurringItem("pc", "Computador", 167.0, false, 5)
            ) else emptyList()
            save(context, defaults)
            return defaults
        }
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                RecurringItem(item.getString("id"), item.getString("name"), item.getDouble("amount"), item.getBoolean("income"), item.optInt("dueDay", 1))
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, items: List<RecurringItem>) {
        val array = JSONArray()
        items.forEach { item -> array.put(JSONObject().apply { put("id", item.id); put("name", item.name); put("amount", item.amount); put("income", item.income); put("dueDay", item.dueDay) }) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }
}
