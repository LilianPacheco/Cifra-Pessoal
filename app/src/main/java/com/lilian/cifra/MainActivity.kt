package com.lilian.cifra

import android.Manifest
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.content.pm.PackageManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID
import java.util.Calendar

private val Background = Color(0xFF071A14)
private val Primary = Color(0xFF123A2D)
private val CardSurface = Color(0xFF0F2C23)
private val CardSurfaceElevated = Color(0xFF163E31)
private val DividerColor = Color(0xFF285044)
private val Accent = Color(0xFFD8F23A)
private val Emerald = Color(0xFF62DFA2)
private val Mint = Color(0xFF193E32)
private val Amber = Color(0xFFE9C84A)
private val Negative = Color(0xFFFF7B7B)
private val Ink = Color(0xFFF6FBF8)
private val SecondaryText = Color(0xFFA7B7B0)
private val Violet = Color(0xFF2F7D62)
private val brl = NumberFormat.getCurrencyInstance(Locale("pt", "BR"))

data class Entry(val title: String, val subtitle: String, val value: Double, val income: Boolean = false)
data class ExpenseDetail(val title: String, val subtitle: String, val value: Double)
data class ExpenseCategory(val title: String, val icon: ImageVector, val items: List<ExpenseDetail>) { val total get() = items.sumOf { it.value } }
data class MonthlyCommitment(val id: String, val title: String, val subtitle: String, val value: Double, val income: Boolean)
data class CardPurchase(
    val title: String,
    val subtitle: String,
    val value: Double,
    val countsInLimit: Boolean,
    val transactionId: String? = null,
    val installments: Int = 1
)
data class MonthSummary(
    val label: String,
    val income: Double,
    val expenses: Double,
    val reserve: Double,
    val invoice: Double,
    val installments: List<String>
) { val available: Double get() = income - expenses - reserve }

private val appMonths = listOf(
    MonthSummary("Julho de 2026", 0.0, 0.0, 0.0, 0.0, emptyList()),
    MonthSummary("Agosto de 2026", 2500.0, 1057.39, 500.0, 626.39, listOf("Guarda-roupa · 2/7 · R$ 130,60", "Roupas · 2/2 · última parcela · R$ 129,99")),
    MonthSummary("Setembro de 2026", 2500.0, 671.50, 500.0, 240.50, listOf("Guarda-roupa · 3/7 · R$ 130,60")),
    MonthSummary("Outubro de 2026", 2500.0, 671.50, 500.0, 240.50, listOf("Guarda-roupa · 4/7 · R$ 130,60")),
    MonthSummary("Novembro de 2026", 2500.0, 671.50, 500.0, 240.50, listOf("Guarda-roupa · 5/7 · R$ 130,60")),
    MonthSummary("Dezembro de 2026", 2500.0, 671.50, 500.0, 240.50, listOf("Guarda-roupa · 6/7 · R$ 130,60")),
    MonthSummary("Janeiro de 2027", 2500.0, 671.50, 500.0, 240.50, listOf("Guarda-roupa · 7/7 · última parcela · R$ 130,60")),
    MonthSummary("Fevereiro de 2027", 2500.0, 540.90, 500.0, 109.90, emptyList())
)

private fun CapturedTransaction.monthLabel(offset: Int = 0): String {
    val names = listOf("Janeiro", "Fevereiro", "Março", "Abril", "Maio", "Junho", "Julho", "Agosto", "Setembro", "Outubro", "Novembro", "Dezembro")
    val calendar = Calendar.getInstance().apply { timeInMillis = timestamp; add(Calendar.MONTH, offset) }
    return "${names[calendar.get(Calendar.MONTH)]} de ${calendar.get(Calendar.YEAR)}"
}

private fun CapturedTransaction.occursIn(label: String) = monthLabel() == label
private fun CapturedTransaction.installmentNumberIn(label: String): Int? {
    if (kind != "CREDIT") return if (occursIn(label)) 1 else null
    for (number in 1..installments.coerceAtLeast(1)) {
        if (monthLabel(number) == label) return number
    }
    return null
}
private fun CapturedTransaction.amountDueIn(label: String): Double =
    if (installmentNumberIn(label) != null) amount / installments.coerceAtLeast(1) else 0.0

private fun CapturedTransaction.installmentNumberInInvoiceCycle(label: String): Int? {
    if (kind != "CREDIT") return null
    for (number in 1..installments.coerceAtLeast(1)) {
        if (monthLabel(number - 1) == label) return number
    }
    return null
}

private fun countsTowardCardLimit(title: String): Boolean {
    val normalized = title.lowercase()
    return !normalized.contains("chatgpt") && !normalized.contains("openai") && !normalized.contains("meli+")
}

private fun knownCardPurchases(month: MonthSummary, monthIndex: Int, userConfig: UserConfig, overrides: Map<String, PurchaseOverride>, capturedTransactions: List<CapturedTransaction>): List<CardPurchase> {
    val latestChatGptCharge = capturedTransactions
        .filter { it.source == "Nubank" && it.kind == "CREDIT" && (it.description.contains("chatgpt", true) || it.description.contains("openai", true)) }
        .maxByOrNull { it.timestamp }
    val latestChatGptAmount = latestChatGptCharge?.amount ?: 100.0
    val raw = if (!userConfig.legacyPreset) emptyList() else when (monthIndex) {
        0 -> emptyList()
        1 -> listOf(CardPurchase("Guarda-roupa", "Parcela 2/7", 130.60, true), CardPurchase("Roupas", "Parcela 2/2 · última parcela", 129.99, true), CardPurchase("Mercado", "Alimentação", 105.92, true), CardPurchase("Presente para o pai", "Compra em agosto", 149.98, true), CardPurchase("ChatGPT", "Assinatura · valor atualizado automaticamente", latestChatGptAmount, false), CardPurchase("Meli+", "Assinatura", 9.90, false))
        in 2..6 -> listOf(CardPurchase("Guarda-roupa", "Parcela ${monthIndex + 1}/7" + if (monthIndex == 6) " · última parcela" else "", 130.60, true), CardPurchase("ChatGPT", "Assinatura · valor atualizado automaticamente", latestChatGptAmount, false), CardPurchase("Meli+", "Assinatura", 9.90, false))
        else -> listOf(CardPurchase("ChatGPT", "Assinatura · valor atualizado automaticamente", latestChatGptAmount, false), CardPurchase("Meli+", "Assinatura", 9.90, false))
    }
    return raw.map { purchase ->
        val id = "known|${month.label}|${purchase.title}"
        val saved = if (purchase.title.equals("ChatGPT", true) && latestChatGptCharge != null) null else overrides[id]
        purchase.copy(
            title = saved?.title ?: purchase.title,
            value = saved?.amount ?: purchase.value,
            installments = saved?.installments ?: purchase.installments,
            countsInLimit = saved?.countsInLimit ?: purchase.countsInLimit,
            subtitle = if ((saved?.installments ?: purchase.installments) > 1) "${saved?.installments ?: purchase.installments}x de ${brl.format((saved?.amount ?: purchase.value) / (saved?.installments ?: purchase.installments))}" else purchase.subtitle,
            transactionId = id
        )
    }
}

private fun List<CardPurchase>.withoutAutomaticallyImportedDuplicates(imported: List<CardPurchase>): List<CardPurchase> = filterNot { known ->
    imported.any { automatic ->
        val auto = automatic.title.lowercase()
        (known.title.equals("ChatGPT", true) && (auto.contains("chatgpt") || auto.contains("openai"))) ||
            (known.title.equals("Meli+", true) && auto.contains("meli+"))
    }
}

class MainActivity : ComponentActivity() {
    private var capturedTransactions by mutableStateOf<List<CapturedTransaction>>(emptyList())
    private var notificationAccessEnabled by mutableStateOf(false)
    private var accountBalances by mutableStateOf(AccountBalances())
    private var userConfig by mutableStateOf(UserConfig())
    private var completedCommitments by mutableStateOf<Set<String>>(emptySet())
    private var recurringItems by mutableStateOf<List<RecurringItem>>(emptyList())
    private var transactionReceiverRegistered = false
    private val transactionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refreshLocalData()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshLocalData()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1706)
        }
        setContent {
            CifraTheme {
                CifraApp(
                    capturedTransactions, notificationAccessEnabled, accountBalances, userConfig, completedCommitments, recurringItems,
                    onRequestNotificationAccess = { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) },
                    onRefreshNotifications = { refreshNotificationCapture() },
                    onAddTransaction = { TransactionStore.add(this, it); refreshLocalData() },
                    onDeleteTransaction = { TransactionStore.delete(this, it); refreshLocalData() },
                    onUpdateTransaction = { TransactionStore.update(this, it); refreshLocalData() },
                    onToggleCommitment = { completedCommitments = MonthlyChecklistStore.toggle(this, it) },
                    onSaveRecurringItems = { RecurringItemStore.save(this, it); recurringItems = it },
                    onSaveBalances = { BalanceStore.save(this, it); refreshLocalData() },
                    onSaveConfig = { UserConfigStore.save(this, it); refreshLocalData() }
                )
            }
        }
    }

    override fun onResume() { super.onResume(); refreshLocalData() }

    override fun onStart() {
        super.onStart()
        if (!transactionReceiverRegistered) {
            ContextCompat.registerReceiver(this, transactionReceiver, IntentFilter(ACTION_TRANSACTIONS_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            transactionReceiverRegistered = true
        }
    }

    override fun onStop() {
        if (transactionReceiverRegistered) {
            unregisterReceiver(transactionReceiver)
            transactionReceiverRegistered = false
        }
        super.onStop()
    }

    private fun refreshLocalData() {
        capturedTransactions = TransactionStore.load(this)
        accountBalances = BalanceStore.load(this)
        val hasExistingData = capturedTransactions.isNotEmpty() || getSharedPreferences("cifra_balances", Context.MODE_PRIVATE).all.isNotEmpty()
        userConfig = UserConfigStore.loadOrMigrate(this, hasExistingData)
        recurringItems = RecurringItemStore.load(this, userConfig.legacyPreset)
        completedCommitments = MonthlyChecklistStore.load(this)
        val listener = ComponentName(this, CifraNotificationListener::class.java)
        notificationAccessEnabled = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName) ||
            Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(listener.flattenToString()) == true
        if (notificationAccessEnabled) NotificationListenerService.requestRebind(listener)
    }

    private fun refreshNotificationCapture() {
        val listener = ComponentName(this, CifraNotificationListener::class.java)
        if (!notificationAccessEnabled) {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            return
        }
        Toast.makeText(this, "Buscando compras e Pix recentes…", Toast.LENGTH_SHORT).show()
        NotificationListenerService.requestUnbind(listener)
        Handler(Looper.getMainLooper()).postDelayed({
            NotificationListenerService.requestRebind(listener)
            Handler(Looper.getMainLooper()).postDelayed({ refreshLocalData() }, 1800)
        }, 700)
    }
}

@Composable
private fun CifraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(primary = Accent, secondary = Emerald, background = Background, surface = CardSurface, onBackground = Ink, onSurface = Ink),
        typography = Typography(),
        content = content
    )
}

@Composable
private fun OnboardingScreen(onSave: (UserConfig) -> Unit) {
    var draft by remember { mutableStateOf(UserConfig()) }
    Column(
        Modifier.fillMaxSize().background(Background).verticalScroll(rememberScrollState()).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Spacer(Modifier.height(20.dp))
        Box(Modifier.size(58.dp).background(Accent, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Paid, null, tint = Background, modifier = Modifier.size(32.dp))
        }
        Text("Seu dinheiro, do seu jeito", color = Ink, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("Configure o básico para o Cifra montar seu painel. Os dados ficam somente neste aparelho.", color = SecondaryText, lineHeight = 21.sp)
        ConfigFields(draft) { draft = it }
        Button(
            onClick = { onSave(draft.copy(configured = true, name = draft.name.trim())) },
            enabled = draft.name.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(54.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Background),
            shape = RoundedCornerShape(16.dp)
        ) { Text("Criar meu Cifra", fontWeight = FontWeight.Bold) }
        Text("Você poderá alterar tudo depois nas configurações.", color = SecondaryText, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun ConfigDialog(config: UserConfig, title: String, onDismiss: () -> Unit, onSave: (UserConfig) -> Unit) {
    var draft by remember(config) { mutableStateOf(config) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) { ConfigFields(draft) { draft = it } } },
        confirmButton = { Button(onClick = { onSave(draft.copy(configured = true, name = draft.name.trim())) }, enabled = draft.name.isNotBlank()) { Text("Salvar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun ConfigFields(config: UserConfig, onChange: (UserConfig) -> Unit) {
    fun number(text: String) = text.replace(".", "").replace(",", ".").toDoubleOrNull() ?: 0.0
    fun value(amount: Double) = if (amount == 0.0) "" else String.format(Locale.US, "%.2f", amount).replace(".", ",")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(config.name, { onChange(config.copy(name = it)) }, label = { Text("Nome ou apelido") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        MoneyConfigField("Reserva planejada", value(config.reserveGoal)) { onChange(config.copy(reserveGoal = number(it))) }
    }
}

@Composable
private fun MoneyConfigField(label: String, value: String, onChange: (String) -> Unit) {
    var text by remember { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it.filter { character -> character.isDigit() || character == ',' || character == '.' }
            onChange(text)
        },
        label = { Text(label) },
        prefix = { Text("R$ ") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
}

@Composable
private fun CifraApp(
    capturedTransactions: List<CapturedTransaction>, notificationAccessEnabled: Boolean, accountBalances: AccountBalances, userConfig: UserConfig,
    completedCommitments: Set<String>, recurringItems: List<RecurringItem>,
    onRequestNotificationAccess: () -> Unit, onRefreshNotifications: () -> Unit, onAddTransaction: (CapturedTransaction) -> Unit,
    onDeleteTransaction: (String) -> Unit, onUpdateTransaction: (CapturedTransaction) -> Unit, onSaveBalances: (AccountBalances) -> Unit,
    onToggleCommitment: (String) -> Unit, onSaveRecurringItems: (List<RecurringItem>) -> Unit, onSaveConfig: (UserConfig) -> Unit
) {
    if (!userConfig.configured) {
        OnboardingScreen(onSaveConfig)
        return
    }
    var tab by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    val now = remember { Calendar.getInstance() }
    val currentMonthIndex = remember {
        val monthNames = listOf("Janeiro", "Fevereiro", "Março", "Abril", "Maio", "Junho", "Julho", "Agosto", "Setembro", "Outubro", "Novembro", "Dezembro")
        val expected = "${monthNames[now.get(Calendar.MONTH)]} de ${now.get(Calendar.YEAR)}"
        appMonths.indexOfFirst { it.label == expected }.takeIf { it >= 0 } ?: 0
    }
    val purchaseOverrides = PurchaseOverrideStore.load(LocalContext.current)
    val configuredMonths = appMonths.mapIndexed { index, month ->
        val recurringIncome = recurringItems.filter { it.income }.sumOf { it.amount }
        val recurringExpenses = recurringItems.filterNot { it.income }.sumOf { it.amount }
        val capturedInvoicePurchases = capturedTransactions.filter { it.kind == "CREDIT" && it.direction == "OUT" && it.installmentNumberIn(month.label) != null }.map {
            CardPurchase(it.description, "Parcela ${it.installmentNumberIn(month.label)}/${it.installments}", it.amountDueIn(month.label), it.countsInLimit ?: countsTowardCardLimit(it.description), it.id, it.installments)
        }
        val knownInvoicePurchases = if (index > 0) knownCardPurchases(appMonths[index - 1], index - 1, userConfig, purchaseOverrides, capturedTransactions).withoutAutomaticallyImportedDuplicates(capturedInvoicePurchases) else emptyList()
        val calculatedInvoice = (knownInvoicePurchases + capturedInvoicePurchases).sumOf { it.value }
        if (index == currentMonthIndex || (!userConfig.legacyPreset && index >= currentMonthIndex)) {
            val accountExpenses = capturedTransactions.filter { it.kind != "CREDIT" && it.direction == "OUT" && it.occursIn(month.label) }.sumOf { it.amount }
            month.copy(
                income = recurringIncome,
                expenses = recurringExpenses + accountExpenses + calculatedInvoice,
                reserve = userConfig.reserveGoal,
                invoice = calculatedInvoice,
                installments = if (userConfig.legacyPreset) month.installments else emptyList()
            )
        } else if (userConfig.legacyPreset && index > currentMonthIndex) {
            val accountExpenses = capturedTransactions.filter { it.kind != "CREDIT" && it.direction == "OUT" && it.occursIn(month.label) }.sumOf { it.amount }
            month.copy(
                income = recurringIncome,
                expenses = recurringExpenses + accountExpenses + calculatedInvoice,
                reserve = userConfig.reserveGoal,
                invoice = calculatedInvoice
            )
        } else month
    }
    var monthIndex by remember { mutableIntStateOf(currentMonthIndex) }
    val selectedMonth = configuredMonths[monthIndex]
    val tabs = listOf("Início", "Contas", "Meses")
    val icons = listOf(Icons.Outlined.Home, Icons.Outlined.CreditCard, Icons.Outlined.CalendarMonth)
    Scaffold(
        containerColor = Background,
        topBar = {
            CifraTopBar(
                month = selectedMonth.label,
                canGoBack = monthIndex > 0,
                canGoForward = monthIndex < configuredMonths.lastIndex,
                onPrevious = { if (monthIndex > 0) monthIndex -= 1 },
                onNext = { if (monthIndex < configuredMonths.lastIndex) monthIndex += 1 },
                onSettings = { showSettings = true }
            )
        },
        bottomBar = {
            NavigationBar(containerColor = CardSurface) {
                tabs.forEachIndexed { index, title ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Icon(icons[index], contentDescription = title) },
                        label = { Text(title, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Background, selectedTextColor = Accent, indicatorColor = Accent, unselectedIconColor = SecondaryText, unselectedTextColor = SecondaryText)
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> HomeScreen(month = selectedMonth, nextMonth = configuredMonths.getOrNull(monthIndex + 1), monthIndex = monthIndex, capturedTransactions = capturedTransactions, userConfig = userConfig, recurringItems = recurringItems, completedCommitments = completedCommitments, onToggleCommitment = onToggleCommitment, onSaveRecurringItems = onSaveRecurringItems, onOpenCard = { tab = 1 })
                1 -> CardScreen(selectedMonth, monthIndex, capturedTransactions, notificationAccessEnabled, accountBalances, userConfig, onRequestNotificationAccess, onRefreshNotifications, onAddTransaction, onDeleteTransaction, onUpdateTransaction, onSaveBalances, onSaveConfig)
                else -> MonthsScreen(months = configuredMonths, selectedIndex = monthIndex, currentMonthIndex = currentMonthIndex, recurringItems = recurringItems, capturedTransactions = capturedTransactions, accountBalances = accountBalances, userConfig = userConfig, onSelect = { monthIndex = it })
            }
        }
    }
    if (showSettings) ConfigDialog(userConfig, "Configurações do Cifra", onDismiss = { showSettings = false }) {
        onSaveConfig(it)
        showSettings = false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CifraTopBar(month: String, canGoBack: Boolean, canGoForward: Boolean, onPrevious: () -> Unit, onNext: () -> Unit, onSettings: () -> Unit) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Background, titleContentColor = Color.White),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(38.dp).background(Color.White.copy(alpha = .16f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Paid, null, tint = Color.White)
                }
                Column { Text("Cifra", fontWeight = FontWeight.Bold, fontSize = 21.sp); Text(month, fontSize = 11.sp, color = Color.White.copy(alpha = .75f)) }
            }
        },
        actions = {
            IconButton(onClick = onPrevious, enabled = canGoBack) { Icon(Icons.Outlined.ChevronLeft, "Mês anterior", tint = Color.White.copy(alpha = if (canGoBack) 1f else .35f)) }
            IconButton(onClick = onNext, enabled = canGoForward) { Icon(Icons.Outlined.ChevronRight, "Próximo mês", tint = Color.White.copy(alpha = if (canGoForward) 1f else .35f)) }
            IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "Configurações", tint = Color.White) }
        }
    )
}

@Composable
private fun HomeScreen(
    month: MonthSummary, nextMonth: MonthSummary?, monthIndex: Int, capturedTransactions: List<CapturedTransaction>, userConfig: UserConfig,
    recurringItems: List<RecurringItem>, completedCommitments: Set<String>, onToggleCommitment: (String) -> Unit,
    onSaveRecurringItems: (List<RecurringItem>) -> Unit, onOpenCard: () -> Unit
) {
    val context = LocalContext.current
    val activityTransactions = capturedTransactions.filter { it.occursIn(month.label) }
    val dueCreditTransactions = capturedTransactions.filter { it.kind == "CREDIT" && it.direction == "OUT" && it.installmentNumberIn(month.label) != null }
    val accountTransactions = capturedTransactions.filter { it.kind != "CREDIT" && it.occursIn(month.label) }
    val automaticEntries = activityTransactions.map {
        Entry(it.description, "${it.source} · ${if (it.kind == "CREDIT") "crédito · paga no mês seguinte" else if (it.kind == "MANUAL") "manual" else "automático"}", it.amount, it.direction == "IN")
    }
    val plannedEntries = if (!userConfig.legacyPreset) emptyList() else when (monthIndex) {
        0 -> emptyList()
        1 -> listOf(
            Entry("Salário Senac", "Receita · 01 ago.", 1200.0, true), Entry("Dentista", "Saúde · Pix · 05 ago.", 89.0),
            Entry("Pacote 6 unhas", "Cuidados · Pix · 05 ago.", 175.0), Entry("PC", "Compras · Pix · 05 ago.", 167.0),
            Entry("Salário AWL", "Receita · 08 ago.", 1300.0, true), Entry("Guarda-roupa", "Nubank · parcela 2/7", 130.60),
            Entry("Roupas", "Nubank · parcela 2/2", 129.99), Entry("Mercado", "Nubank · Alimentação", 105.92),
            Entry("Presente para o pai", "Nubank · 06 ago.", 149.98),
            Entry("ChatGPT", "Assinatura · fora do teto", 100.0), Entry("Meli+", "Assinatura · fora do teto", 9.90)
        )
        else -> buildList {
            add(Entry("Salário Senac", "Receita · início do mês", 1200.0, true)); add(Entry("Dentista", "Saúde · Pix", 89.0)); add(Entry("Pacote 6 unhas", "Cuidados · Pix", 175.0)); add(Entry("PC", "Compras · Pix", 167.0)); add(Entry("Salário AWL", "Receita · início do mês", 1300.0, true))
            if (month.installments.isNotEmpty()) add(Entry("Guarda-roupa", "Nubank · ${month.installments.first().substringAfter("· ").substringBeforeLast(" ·")}", 130.60))
            add(Entry("ChatGPT", "Assinatura · fora do teto", 100.0)); add(Entry("Meli+", "Assinatura · fora do teto", 9.90))
        }
    }
    val entries = plannedEntries + automaticEntries
    val dueCredit = dueCreditTransactions.sumOf { it.amountDueIn(month.label) }
    val accountExpenses = accountTransactions.filter { it.direction == "OUT" }.sumOf { it.amount }
    val automaticIncome = accountTransactions.filter { it.direction == "IN" }.sumOf { it.amount }
    val baseIncome = if (monthIndex >= 1) recurringItems.filter { it.income }.sumOf { it.amount } else month.income
    val baseExpenses = if (monthIndex >= 1) recurringItems.filterNot { it.income }.sumOf { it.amount } else month.expenses
    val reserve = if (monthIndex >= 1) userConfig.reserveGoal else month.reserve
    val displayedIncome = baseIncome + automaticIncome
    val displayedInvoice = month.invoice
    val displayedExpenses = baseExpenses + accountExpenses + displayedInvoice
    val displayedAvailable = displayedIncome - displayedExpenses - reserve
    val cycleCharges = capturedTransactions.filter { it.source == "Nubank" && it.kind == "CREDIT" && it.direction == "OUT" && it.installmentNumberInInvoiceCycle(month.label) != null }.map {
        CardPurchase(it.description, "Cobrança da fatura", it.amount / it.installments.coerceAtLeast(1), it.countsInLimit ?: countsTowardCardLimit(it.description), it.id, it.installments)
    }
    val knownCycleCharges = knownCardPurchases(month, monthIndex, userConfig, PurchaseOverrideStore.load(context), capturedTransactions).withoutAutomaticallyImportedDuplicates(cycleCharges)
    val cardInvoiceTotal = (knownCycleCharges + cycleCharges).sumOf { it.value }
    val limitSpent = (knownCycleCharges + cycleCharges).filter { it.countsInLimit }.sumOf { it.value }
    val limitRemaining = userConfig.cardBudget - limitSpent
    val budgetProgress = if (userConfig.cardBudget > 0) (limitSpent / userConfig.cardBudget).toFloat().coerceIn(0f, 1f) else 0f
    val commitments = if (monthIndex < 2) emptyList() else recurringItems.map {
        MonthlyCommitment(it.id, it.name, if (it.income) "Marque quando receber" else "Vence dia ${it.dueDay.toString().padStart(2, '0')}", it.amount, it.income)
    }
    val checklistPrefix = month.label
    val cardCharges = knownCycleCharges + cycleCharges
    fun details(items: List<CardPurchase>) = items.map { ExpenseDetail(it.title, it.subtitle, it.value) }
    val subscriptionCharges = cardCharges.filter { !countsTowardCardLimit(it.title) }
    val transportCharges = cardCharges.filter { it.countsInLimit && (it.title.contains("uber", true) || it.title.contains("99", true)) }
    val foodCharges = cardCharges.filter { it.countsInLimit && (it.title.contains("kalzone", true) || it.title.contains("restaurante", true) || it.title.contains("atacadista", true) || (it.title.contains("mercado", true) && !it.title.contains("mercadolivre", true))) }
    val shoppingCharges = cardCharges.filterNot { it in subscriptionCharges || it in transportCharges || it in foodCharges }
    val categories = listOf(
        ExpenseCategory("Compras", Icons.Outlined.ShoppingBag, details(shoppingCharges)),
        ExpenseCategory("Alimentação", Icons.Outlined.Restaurant, details(foodCharges)),
        ExpenseCategory("Transporte", Icons.Outlined.DirectionsCar, details(transportCharges)),
        ExpenseCategory("Assinaturas", Icons.Outlined.Subscriptions, details(subscriptionCharges))
    )
    var selectedCategory by remember(month.label) { mutableStateOf<ExpenseCategory?>(null) }
    var managerIncome by remember { mutableStateOf<Boolean?>(null) }
    if (managerIncome != null) {
        RecurringManagerScreen(
            income = managerIncome == true,
            items = recurringItems.filter { it.income == (managerIncome == true) },
            onBack = { managerIncome = null },
            onSave = { item -> onSaveRecurringItems(recurringItems.filterNot { it.id == item.id } + item) },
            onDelete = { id -> onSaveRecurringItems(recurringItems.filterNot { it.id == id }) }
        )
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column {
            Text("Olá, ${userConfig.name} 👋", fontWeight = FontWeight.Bold, fontSize = 24.sp, color = Ink)
            Text("Aqui está o seu mês de ${month.label.substringBefore(" de ").lowercase()}.", color = SecondaryText, fontSize = 14.sp)
        }
        if (commitments.isNotEmpty()) {
            Text("Início do mês", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Ink)
            Surface(modifier = Modifier.fillMaxWidth(), color = CardSurface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, DividerColor)) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    commitments.forEach { commitment ->
                        val key = "$checklistPrefix:${commitment.id}"
                        val checked = key in completedCommitments
                        Row(Modifier.fillMaxWidth().clickable { onToggleCommitment(key) }.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = checked, onCheckedChange = { onToggleCommitment(key) }, colors = CheckboxDefaults.colors(checkedColor = Emerald))
                            Column(Modifier.weight(1f)) {
                                Text(commitment.title, fontWeight = FontWeight.SemiBold, color = if (checked) SecondaryText else Ink)
                                Text(if (checked) (if (commitment.income) "Recebido" else "Pago") else commitment.subtitle, color = if (checked) Emerald else SecondaryText, fontSize = 11.sp)
                            }
                            Text(brl.format(commitment.value), fontWeight = FontWeight.Bold, color = if (commitment.income) Emerald else Ink)
                        }
                    }
                }
            }
        }
        Surface(modifier = Modifier.fillMaxWidth(), color = CardSurfaceElevated, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, DividerColor)) {
            Column(Modifier.padding(24.dp)) {
                val projectedMonth = nextMonth ?: month
                Text("SOBRA PLANEJADA PARA ${projectedMonth.label.uppercase()}", color = Color.White.copy(alpha = .72f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = .8.sp)
                Spacer(Modifier.height(8.dp)); Text(brl.format(projectedMonth.available), color = Accent, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text("Projeção do próximo mês após despesas e reserva; não é saldo bancário", color = Color.White.copy(alpha = .72f), fontSize = 13.sp)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MiniMetric("Receitas", displayedIncome, Icons.Outlined.SouthWest, Modifier.weight(1f), Emerald, onClick = { managerIncome = true })
            MiniMetric("Despesas", displayedExpenses, Icons.Outlined.NorthEast, Modifier.weight(1f), Negative, onClick = { managerIncome = false })
        }
        Surface(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenCard), color = CardSurface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, DividerColor)) {
            Column(Modifier.padding(18.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column { Text("Orçamento do cartão", color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp); Text("${brl.format(limitSpent)} de ${brl.format(userConfig.cardBudget)}", color = SecondaryText, fontSize = 13.sp) }
                    Text("${(budgetProgress * 100).toInt()}%", color = if (budgetProgress >= .8f) Amber else Accent, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                Spacer(Modifier.height(14.dp)); LinearProgressIndicator(progress = { budgetProgress }, color = if (budgetProgress >= .8f) Amber else Accent, trackColor = DividerColor, modifier = Modifier.fillMaxWidth().height(8.dp))
                Spacer(Modifier.height(8.dp)); Text("Fatura: ${brl.format(cardInvoiceTotal)} · Restam no teto: ${brl.format(limitRemaining)} →", color = SecondaryText, fontSize = 11.sp)
            }
        }
        InsightCard(limitSpent, limitRemaining)
        Text("Gastos desta fatura", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Ink)
        Surface(modifier = Modifier.fillMaxWidth(), color = CardSurface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, DividerColor)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (cardInvoiceTotal > 0) {
                    categories.forEach { category ->
                        SpendCategoryRow(category.title, category.total, if (cardInvoiceTotal > 0) ((category.total / cardInvoiceTotal) * 100).toInt() else 0, category.icon) { selectedCategory = category }
                    }
                    HorizontalDivider(color = DividerColor)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Total dos gastos", color = SecondaryText, fontWeight = FontWeight.SemiBold)
                        Text(brl.format(cardInvoiceTotal), color = Negative, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    }
                } else Text("Nenhum gasto registrado neste mês.", color = SecondaryText)
            }
        }
        Spacer(Modifier.height(10.dp))
    }
    selectedCategory?.let { category -> CategoryDetailsDialog(category, onDismiss = { selectedCategory = null }) }
}

@Composable
private fun MiniMetric(title: String, value: Double, icon: ImageVector, modifier: Modifier = Modifier, color: Color = Accent, onClick: (() -> Unit)? = null) {
    Surface(modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier), color = CardSurface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, DividerColor)) {
        Column(Modifier.padding(16.dp)) { Icon(icon, null, tint = color); Spacer(Modifier.height(12.dp)); Text(title, color = SecondaryText, fontSize = 12.sp); Text(brl.format(value), color = color, fontWeight = FontWeight.Bold, fontSize = 17.sp) }
    }
}

@Composable
private fun RecurringManagerScreen(
    income: Boolean, items: List<RecurringItem>, onBack: () -> Unit,
    onSave: (RecurringItem) -> Unit, onDelete: (String) -> Unit
) {
    var editing by remember { mutableStateOf<RecurringItem?>(null) }
    var adding by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "Voltar") }
            Column(Modifier.weight(1f)) {
                Text(if (income) "Gerenciar receitas" else "Gerenciar despesas fixas", fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Text(if (income) "Valores que você recebe todos os meses" else "Contas recorrentes; cartão e gastos automáticos são gerenciados na fatura", color = SecondaryText, fontSize = 11.sp)
            }
        }
        Surface(modifier = Modifier.fillMaxWidth(), color = CardSurfaceElevated, shape = RoundedCornerShape(16.dp)) {
            Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Total mensal", color = SecondaryText)
                Text(brl.format(items.sumOf { it.amount }), color = if (income) Emerald else Negative, fontWeight = FontWeight.Bold, fontSize = 21.sp)
            }
        }
        if (items.isEmpty()) AccountEmptyState(if (income) Icons.Outlined.SouthWest else Icons.Outlined.ReceiptLong, "Nenhum item", "Adicione ${if (income) "uma receita" else "uma despesa fixa"}.")
        items.sortedBy { it.dueDay }.forEach { item ->
            Surface(modifier = Modifier.fillMaxWidth(), color = CardSurface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, DividerColor)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(item.name, fontWeight = FontWeight.SemiBold); Text("Dia ${item.dueDay.toString().padStart(2, '0')}", color = SecondaryText, fontSize = 11.sp) }
                    Text(brl.format(item.amount), fontWeight = FontWeight.Bold)
                    IconButton(onClick = { editing = item }) { Icon(Icons.Outlined.Edit, "Editar", tint = Accent) }
                    IconButton(onClick = { onDelete(item.id) }) { Icon(Icons.Outlined.Delete, "Apagar", tint = Negative) }
                }
            }
        }
        Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Background)) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(7.dp)); Text(if (income) "Adicionar receita" else "Adicionar despesa fixa") }
    }
    val itemToEdit = editing ?: if (adding) RecurringItem(UUID.randomUUID().toString(), "", 0.0, income, 1) else null
    itemToEdit?.let { item -> RecurringItemDialog(item, onDismiss = { editing = null; adding = false }) { onSave(it); editing = null; adding = false } }
}

@Composable
private fun RecurringItemDialog(item: RecurringItem, onDismiss: () -> Unit, onSave: (RecurringItem) -> Unit) {
    var name by remember(item.id) { mutableStateOf(item.name) }
    var value by remember(item.id) { mutableStateOf(if (item.amount > 0) item.amount.toString().replace('.', ',') else "") }
    var day by remember(item.id) { mutableStateOf(item.dueDay.toString()) }
    val amount = value.replace(",", ".").toDoubleOrNull()
    val dueDay = day.toIntOrNull()
    val valid = name.isNotBlank() && amount != null && amount > 0 && dueDay != null && dueDay in 1..31
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (item.name.isBlank()) "Novo item" else "Editar item") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true)
            OutlinedTextField(value, { value = it.filter { c -> c.isDigit() || c == ',' || c == '.' } }, label = { Text("Valor mensal") }, prefix = { Text("R$ ") }, singleLine = true)
            OutlinedTextField(day, { day = it.filter(Char::isDigit).take(2) }, label = { Text(if (item.income) "Dia previsto para receber" else "Dia do vencimento") }, singleLine = true)
        } },
        confirmButton = { Button(onClick = { onSave(item.copy(name = name.trim(), amount = amount!!, dueDay = dueDay!!)) }, enabled = valid) { Text("Salvar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun InsightCard(spent: Double, remaining: Double) {
    Surface(modifier = Modifier.fillMaxWidth(), color = Color(0xFF302F1C), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Amber.copy(alpha = .45f))) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(40.dp).background(Amber.copy(alpha = .16f), CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.AutoAwesome, null, tint = Amber) }
            Column(Modifier.padding(start = 12.dp)) {
                Text("Insight do Cifra", color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("Você usou ${(spent / 10).toInt()}% do orçamento do cartão. Mantendo esse ritmo, ainda tem ${brl.format(remaining)} para compras planejadas.", color = SecondaryText, fontSize = 13.sp, lineHeight = 19.sp)
            }
        }
    }
}

@Composable
private fun SpendCategoryRow(title: String, value: Double, percentage: Int, icon: ImageVector, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 3.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).background(CardSurfaceElevated, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Accent, modifier = Modifier.size(19.dp)) }
            Column(Modifier.padding(start = 11.dp).weight(1f)) { Text(title, color = Ink, fontWeight = FontWeight.SemiBold); Text("$percentage% dos gastos", color = SecondaryText, fontSize = 11.sp) }
            Text(brl.format(value), color = Ink, fontWeight = FontWeight.Bold)
            Icon(Icons.Outlined.ChevronRight, "Ver gastos", tint = SecondaryText, modifier = Modifier.padding(start = 6.dp))
        }
        Spacer(Modifier.height(8.dp)); LinearProgressIndicator(progress = { percentage / 100f }, color = Accent, trackColor = DividerColor, modifier = Modifier.fillMaxWidth().height(5.dp))
    }
}

@Composable
private fun CategoryDetailsDialog(category: ExpenseCategory, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(category.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (category.items.isEmpty()) Text("Nenhum gasto nesta categoria.", color = SecondaryText)
                category.items.forEachIndexed { index, item ->
                    if (index > 0) HorizontalDivider(color = DividerColor)
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(item.title, fontWeight = FontWeight.SemiBold); Text(item.subtitle, color = SecondaryText, fontSize = 11.sp) }
                        Text(brl.format(item.value), fontWeight = FontWeight.Bold)
                    }
                }
                HorizontalDivider(color = DividerColor)
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text("Total", fontWeight = FontWeight.Bold); Text(brl.format(category.total), color = Negative, fontWeight = FontWeight.Bold) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } }
    )
}

@Composable
private fun EntryRow(entry: Entry) {
    Surface(modifier = Modifier.fillMaxWidth(), color = CardSurface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, DividerColor)) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(if (entry.income) Mint else Color(0xFFFFECEE), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(if (entry.income) Icons.Outlined.SouthWest else Icons.Outlined.NorthEast, null, tint = if (entry.income) Emerald else Color(0xFFD9495B))
            }
            Column(Modifier.padding(start = 12.dp).weight(1f)) { Text(entry.title, fontWeight = FontWeight.SemiBold); Text(entry.subtitle, color = Color.Gray, fontSize = 11.sp) }
            Text((if (entry.income) "+ " else "− ") + brl.format(entry.value), color = if (entry.income) Emerald else Ink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

@Composable
private fun CardScreen(
    month: MonthSummary, monthIndex: Int, capturedTransactions: List<CapturedTransaction>, notificationAccessEnabled: Boolean,
    accountBalances: AccountBalances, userConfig: UserConfig, onRequestNotificationAccess: () -> Unit, onRefreshNotifications: () -> Unit,
    onAddTransaction: (CapturedTransaction) -> Unit, onDeleteTransaction: (String) -> Unit, onUpdateTransaction: (CapturedTransaction) -> Unit,
    onSaveBalances: (AccountBalances) -> Unit, onSaveConfig: (UserConfig) -> Unit
) {
    val context = LocalContext.current
    val monthTransactions = capturedTransactions.filter { it.occursIn(month.label) }
    var selectedAccount by remember { mutableStateOf("Nubank") }
    var showTransactionDialog by remember { mutableStateOf(false) }
    var showBalanceDialog by remember { mutableStateOf(false) }
    var showBudgetDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var purchaseOverrides by remember { mutableStateOf(PurchaseOverrideStore.load(context)) }
    val configuredKnownPurchases = knownCardPurchases(month, monthIndex, userConfig, purchaseOverrides, capturedTransactions)
    val importedNubank = monthTransactions.filter { it.source == "Nubank" && it.kind == "CREDIT" }.map {
        CardPurchase(
            title = it.description,
            subtitle = if (it.installments > 1) "${it.installments}x de ${brl.format(it.amount / it.installments)} · total ${brl.format(it.amount)}" else "À vista? Toque no lápis para confirmar",
            value = it.amount / it.installments.coerceAtLeast(1),
            countsInLimit = it.countsInLimit ?: countsTowardCardLimit(it.description),
            transactionId = it.id,
            installments = it.installments
        )
    }
    val installmentsFromPreviousMonths = capturedTransactions.filter {
        it.source == "Nubank" && it.kind == "CREDIT" && it.direction == "OUT" && !it.occursIn(month.label) && it.installmentNumberInInvoiceCycle(month.label) != null
    }.map { transaction ->
        val number = transaction.installmentNumberInInvoiceCycle(month.label) ?: 1
        CardPurchase(
            title = transaction.description,
            subtitle = if (transaction.installments > 1) "Parcela $number/${transaction.installments} · total ${brl.format(transaction.amount)}" else "Compra à vista · vencimento neste mês",
            value = transaction.amountDueIn(month.label),
            countsInLimit = transaction.countsInLimit ?: countsTowardCardLimit(transaction.description),
            transactionId = transaction.id,
            installments = transaction.installments
        )
    }
    val nubankAccountTransactions = monthTransactions.filter { it.source == "Nubank" && it.kind != "CREDIT" }
    val mercadoTransactions = monthTransactions.filter { it.source == "Mercado Pago" }
    val cashTransactions = monthTransactions.filter { it.source == "Dinheiro" }
    fun isKnownThroughSelectedMonth(transaction: CapturedTransaction): Boolean {
        val transactionIndex = appMonths.indexOfFirst { it.label == transaction.monthLabel() }
        return transactionIndex >= 0 && transactionIndex <= monthIndex
    }
    val cumulativeMercadoTransactions = capturedTransactions.filter { it.source == "Mercado Pago" && isKnownThroughSelectedMonth(it) }
    val cumulativeCashTransactions = capturedTransactions.filter { it.source == "Dinheiro" && isKnownThroughSelectedMonth(it) }
    val mercadoBalance = accountBalances.mercadoPago + cumulativeMercadoTransactions.sumOf { if (it.direction == "IN") it.amount else -it.amount }
    val cashBalance = accountBalances.cash + cumulativeCashTransactions.sumOf { if (it.direction == "IN") it.amount else -it.amount }
    val knownPurchases = configuredKnownPurchases.withoutAutomaticallyImportedDuplicates(importedNubank + installmentsFromPreviousMonths)
    val invoicePurchases = knownPurchases + importedNubank + installmentsFromPreviousMonths
    val ceilingPurchases = invoicePurchases
    val invoice = invoicePurchases.sumOf { it.value }
    val countsInLimit = ceilingPurchases.filter { it.countsInLimit }.sumOf { it.value }
    val outsideLimit = ceilingPurchases.filterNot { it.countsInLimit }.sumOf { it.value }
    var editingPurchase by remember { mutableStateOf<CardPurchase?>(null) }
    val editPurchaseById: (String) -> Unit = { id ->
        editingPurchase = capturedTransactions.firstOrNull { it.id == id }?.let { transaction ->
            CardPurchase(transaction.description, "${transaction.installments} parcela(s)", transaction.amount, true, transaction.id, transaction.installments)
        } ?: knownPurchases.firstOrNull { it.transactionId == id }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        NotificationAccessCard(notificationAccessEnabled, onRequestNotificationAccess, onRefreshNotifications)
        Text("Contas e formas de pagamento", fontWeight = FontWeight.Bold, fontSize = 20.sp)
        PaymentMethodRow(userConfig.cardName, "Cartão de crédito · ver fatura", Icons.Outlined.CreditCard, Violet, selected = selectedAccount == "Nubank", onClick = { selectedAccount = "Nubank" }, onRename = { renameTarget = "card" })
        PaymentMethodRow(userConfig.bankAccountName, "Pix, entradas e saídas da conta", Icons.Outlined.AccountBalance, Violet, selected = selectedAccount == "Conta Nubank", onClick = { selectedAccount = "Conta Nubank" }, onRename = { renameTarget = "bank" })
        PaymentMethodRow(userConfig.walletName, "Saldo e movimentações", Icons.Outlined.AccountBalanceWallet, Emerald, brl.format(mercadoBalance), selected = selectedAccount == "Mercado Pago", onClick = { selectedAccount = "Mercado Pago" }, onRename = { renameTarget = "wallet" })
        PaymentMethodRow(userConfig.cashName, "Saldo em espécie", Icons.Outlined.Payments, Color(0xFF9B6B22), brl.format(cashBalance), selected = selectedAccount == "Dinheiro", onClick = { selectedAccount = "Dinheiro" }, onRename = { renameTarget = "cash" })

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { showTransactionDialog = true }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Background)) {
                Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(6.dp)); Text(if (selectedAccount == "Nubank") "Lançar compra" else "Entrada ou saída")
            }
            if (selectedAccount == "Mercado Pago" || selectedAccount == "Dinheiro") OutlinedButton(onClick = { showBalanceDialog = true }, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.Edit, null); Spacer(Modifier.width(6.dp)); Text("Corrigir saldo") }
        }

        when (selectedAccount) {
            "Nubank" -> {
                Surface(modifier = Modifier.fillMaxWidth(), color = Violet, shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.fillMaxWidth().padding(24.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("FATURA ${userConfig.cardName.uppercase()} · ${month.label.uppercase()}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp); Icon(Icons.Outlined.Contactless, null, tint = Color.White) }
                        Spacer(Modifier.height(28.dp)); Text("Compras e parcelas lançadas nesta fatura", color = Color.White.copy(alpha = .7f)); Text(brl.format(invoice), color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold); Text("Pagamento no mês seguinte · vence dia 09", color = Color.White.copy(alpha = .75f))
                    }
                }
                InvoiceSummary(countsInLimit, outsideLimit)
                OutlinedButton(onClick = { showBudgetDialog = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Edit, null); Spacer(Modifier.width(7.dp)); Text("Definir teto mensal · ${brl.format(userConfig.cardBudget)}") }
                if (invoicePurchases.isEmpty() && ceilingPurchases.isEmpty()) AccountEmptyState(Icons.Outlined.CreditCard, "Sem fatura", "Não há gastos conhecidos no Nubank em ${month.label}.") else {
                    InvoiceSection("Fatura completa", invoice, invoicePurchases, onEdit = editPurchaseById)
                    InvoiceSection("Dentro do teto · ${brl.format(userConfig.cardBudget)}", countsInLimit, ceilingPurchases.filter { it.countsInLimit }, accent = Emerald, onEdit = editPurchaseById)
                    InvoiceSection("Não conta no teto", outsideLimit, ceilingPurchases.filterNot { it.countsInLimit }, accent = Color(0xFF8B5E23), onEdit = editPurchaseById)
                }
            }
            "Conta Nubank" -> AccountHistory("Movimentações da conta Nubank", nubankAccountTransactions, onDeleteTransaction)
            "Mercado Pago" -> if (mercadoTransactions.isEmpty()) AccountEmptyState(
                icon = Icons.Outlined.AccountBalanceWallet,
                title = brl.format(mercadoBalance),
                text = "Saldo registrado acumulado até ${month.label}. Corrija o saldo se alguma movimentação não tiver sido capturada."
            ) else AccountBalanceHistory("Mercado Pago", mercadoBalance, accountBalances.mercadoPago, mercadoTransactions, onDeleteTransaction)
            else -> AccountBalanceHistory("Dinheiro", cashBalance, accountBalances.cash, cashTransactions, onDeleteTransaction)
        }
    }
    if (showTransactionDialog) ManualTransactionDialog(selectedAccount, creditMode = selectedAccount == "Nubank", onDismiss = { showTransactionDialog = false }) { description, amount, direction ->
        val source = if (selectedAccount == "Conta Nubank") "Nubank" else selectedAccount
        onAddTransaction(CapturedTransaction(UUID.randomUUID().toString(), source, description, amount, System.currentTimeMillis(), direction, if (selectedAccount == "Nubank") "CREDIT" else "MANUAL"))
        showTransactionDialog = false
    }
    if (showBalanceDialog) BalanceCorrectionDialog(selectedAccount, onDismiss = { showBalanceDialog = false }) { desired ->
        when (selectedAccount) {
            "Mercado Pago" -> onSaveBalances(accountBalances.copy(mercadoPago = desired - cumulativeMercadoTransactions.sumOf { if (it.direction == "IN") it.amount else -it.amount }))
            "Dinheiro" -> onSaveBalances(accountBalances.copy(cash = desired - cumulativeCashTransactions.sumOf { if (it.direction == "IN") it.amount else -it.amount }))
        }
        showBalanceDialog = false
    }
    editingPurchase?.let { purchase ->
        EditCreditPurchaseDialog(purchase, onDismiss = { editingPurchase = null }) { title, amount, installments, countsInLimit ->
            val id = purchase.transactionId ?: return@EditCreditPurchaseDialog
            if (id.startsWith("known|")) {
                purchaseOverrides = PurchaseOverrideStore.save(context, id, PurchaseOverride(title, amount, installments, countsInLimit))
            } else {
                capturedTransactions.firstOrNull { it.id == id }?.let { onUpdateTransaction(it.copy(description = title, amount = amount, installments = installments, countsInLimit = countsInLimit)) }
            }
            editingPurchase = null
        }
    }
    renameTarget?.let { target ->
        val currentName = when (target) { "card" -> userConfig.cardName; "bank" -> userConfig.bankAccountName; "wallet" -> userConfig.walletName; else -> userConfig.cashName }
        RenameAccountDialog(currentName, onDismiss = { renameTarget = null }) { newName ->
            onSaveConfig(when (target) {
                "card" -> userConfig.copy(cardName = newName)
                "bank" -> userConfig.copy(bankAccountName = newName)
                "wallet" -> userConfig.copy(walletName = newName)
                else -> userConfig.copy(cashName = newName)
            })
            renameTarget = null
        }
    }
    if (showBudgetDialog) BudgetDialog(userConfig.cardBudget, onDismiss = { showBudgetDialog = false }) { value -> onSaveConfig(userConfig.copy(cardBudget = value)); showBudgetDialog = false }
}

@Composable
private fun AccountHistory(title: String, transactions: List<CapturedTransaction>, onDelete: (String) -> Unit) {
    if (transactions.isEmpty()) {
        AccountEmptyState(Icons.Outlined.AccountBalance, title, "Nenhum Pix ou movimento da conta foi capturado neste mês.")
        return
    }
    val variation = transactions.sumOf { if (it.direction == "IN") it.amount else -it.amount }
    Surface(modifier = Modifier.fillMaxWidth(), color = CardSurface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, DividerColor)) {
        Column(Modifier.padding(18.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 19.sp)
            Text("Variação capturada: ${if (variation >= 0) "+ " else "− "}${brl.format(kotlin.math.abs(variation))}", color = if (variation >= 0) Emerald else Color(0xFFD9495B), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
            transactions.forEachIndexed { index, item ->
                if (index > 0) HorizontalDivider(color = Color(0xFFE9EEEB))
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (item.direction == "IN") Icons.Outlined.SouthWest else Icons.Outlined.NorthEast, null, tint = if (item.direction == "IN") Emerald else Color(0xFFD9495B))
                    Column(Modifier.padding(start = 10.dp).weight(1f)) { Text(item.description, fontWeight = FontWeight.SemiBold); Text((if (item.direction == "IN") "Entrada" else "Saída") + if (item.kind == "MANUAL") " · manual" else " · automática", color = Color.Gray, fontSize = 11.sp) }
                    Text((if (item.direction == "IN") "+ " else "− ") + brl.format(item.amount), color = if (item.direction == "IN") Emerald else Ink, fontWeight = FontWeight.Bold)
                    IconButton(onClick = { onDelete(item.id) }) { Icon(Icons.Outlined.Delete, "Excluir", tint = Color.Gray) }
                }
            }
        }
    }
}

@Composable
private fun AccountBalanceHistory(name: String, balance: Double, baseBalance: Double, transactions: List<CapturedTransaction>, onDelete: (String) -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth(), color = CardSurface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, DividerColor)) {
        Column(Modifier.padding(18.dp)) {
            Text("$name · saldo atual", color = Color.Gray, fontSize = 12.sp)
            Text(brl.format(balance), color = Emerald, fontWeight = FontWeight.Bold, fontSize = 30.sp)
            Text("Saldo-base ${brl.format(baseBalance)}", color = Color.Gray, fontSize = 11.sp)
            Spacer(Modifier.height(14.dp))
            if (transactions.isEmpty()) Text("Nenhuma movimentação registrada.", color = Color.Gray)
            transactions.forEachIndexed { index, item ->
                if (index > 0) HorizontalDivider(color = Color(0xFFE9EEEB))
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (item.direction == "IN") Icons.Outlined.SouthWest else Icons.Outlined.NorthEast, null, tint = if (item.direction == "IN") Emerald else Color(0xFFD9495B))
                    Column(Modifier.padding(start = 10.dp).weight(1f)) {
                        Text(item.description, fontWeight = FontWeight.SemiBold)
                        Text((if (item.direction == "IN") "Entrada" else "Saída") + if (item.kind == "MANUAL") " · manual" else " · automática", color = Color.Gray, fontSize = 11.sp)
                    }
                    Text((if (item.direction == "IN") "+ " else "− ") + brl.format(item.amount), color = if (item.direction == "IN") Emerald else Ink, fontWeight = FontWeight.Bold)
                    IconButton(onClick = { onDelete(item.id) }) { Icon(Icons.Outlined.Delete, "Excluir", tint = Color.Gray) }
                }
            }
        }
    }
}

@Composable
private fun ManualTransactionDialog(account: String, creditMode: Boolean, onDismiss: () -> Unit, onSave: (String, Double, String) -> Unit) {
    var description by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var direction by remember { mutableStateOf("OUT") }
    val amount = value.replace(",", ".").toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Novo lançamento · $account") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!creditMode) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = direction == "OUT", onClick = { direction = "OUT" }, label = { Text("Saída") }, leadingIcon = { Icon(Icons.Outlined.NorthEast, null) })
                    FilterChip(selected = direction == "IN", onClick = { direction = "IN" }, label = { Text("Entrada") }, leadingIcon = { Icon(Icons.Outlined.SouthWest, null) })
                } else Text("Compra no cartão de crédito · conta no teto de R$ 1.000", color = Violet, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Descrição") }, singleLine = true)
                OutlinedTextField(value = value, onValueChange = { value = it.filter { c -> c.isDigit() || c == ',' || c == '.' } }, label = { Text("Valor") }, prefix = { Text("R$ ") }, singleLine = true)
                Text("Lançamento manual · você poderá excluí-lo depois.", color = Color.Gray, fontSize = 11.sp)
            }
        },
        confirmButton = { Button(onClick = { onSave(description.ifBlank { if (direction == "IN") "Entrada" else "Saída" }, amount!!, direction) }, enabled = amount != null && amount > 0) { Text("Salvar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun BalanceCorrectionDialog(account: String, onDismiss: () -> Unit, onSave: (Double) -> Unit) {
    var value by remember { mutableStateOf("") }
    val amount = value.replace(",", ".").toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Corrigir saldo · $account") },
        text = { Column { Text("Informe o saldo que aparece agora na conta.", color = Color.Gray); Spacer(Modifier.height(12.dp)); OutlinedTextField(value = value, onValueChange = { value = it.filter { c -> c.isDigit() || c == ',' || c == '.' } }, label = { Text("Saldo atual") }, prefix = { Text("R$ ") }, singleLine = true) } },
        confirmButton = { Button(onClick = { onSave(amount!!) }, enabled = amount != null && amount >= 0) { Text("Atualizar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun NotificationAccessCard(enabled: Boolean, onRequestAccess: () -> Unit, onRefresh: () -> Unit) {
    Surface(
        color = if (enabled) Mint else Color(0xFFFFF4E5),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, if (enabled) Emerald.copy(alpha = .25f) else Color(0xFFE8B45B))
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (enabled) Icons.Outlined.VerifiedUser else Icons.Outlined.NotificationsActive, null, tint = if (enabled) Emerald else Color(0xFF8B5E23))
            Column(Modifier.padding(horizontal = 12.dp).weight(1f)) {
                Text(if (enabled) "Captura automática ativada" else "Ative a captura automática", fontWeight = FontWeight.Bold)
                Text(
                    if (enabled) "Somente leitura das notificações do Nubank e Mercado Pago."
                    else "O Cifra precisa da permissão de acesso às notificações.",
                    color = Color.Gray, fontSize = 11.sp
                )
            }
            if (!enabled) Button(onClick = onRequestAccess, colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Background)) { Text("Ativar") }
            else OutlinedButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, null); Spacer(Modifier.width(5.dp)); Text("Atualizar") }
        }
    }
}

@Composable
private fun PaymentMethodRow(title: String, subtitle: String, icon: ImageVector, color: Color, value: String? = null, selected: Boolean, onClick: () -> Unit, onRename: (() -> Unit)? = null) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = if (selected) color.copy(alpha = .24f) else CardSurface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) color else DividerColor)
    ) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).background(color.copy(alpha = .12f), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = color)
            }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = Color.Gray, fontSize = 11.sp)
            }
            if (value != null) Text(value, color = color, fontWeight = FontWeight.Bold)
            if (onRename != null) IconButton(onClick = onRename) { Icon(Icons.Outlined.Edit, "Renomear", tint = Accent) }
            Icon(Icons.Outlined.ChevronRight, "Abrir", tint = color)
        }
    }
}

@Composable
private fun RenameAccountDialog(currentName: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember(currentName) { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Renomear conta") },
        text = { OutlinedTextField(name, { name = it }, label = { Text("Nome exibido") }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { Button(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) { Text("Salvar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun BudgetDialog(currentValue: Double, onDismiss: () -> Unit, onSave: (Double) -> Unit) {
    var value by remember(currentValue) { mutableStateOf(currentValue.toString().replace('.', ',')) }
    val amount = value.replace(",", ".").toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Definir teto mensal") },
        text = { Column { Text("Informe quanto desta fatura você aceita comprometer com as compras marcadas como dentro do teto.", color = SecondaryText); Spacer(Modifier.height(12.dp)); OutlinedTextField(value, { value = it.filter { c -> c.isDigit() || c == ',' || c == '.' } }, label = { Text("Teto do cartão") }, prefix = { Text("R$ ") }, singleLine = true) } },
        confirmButton = { Button(onClick = { onSave(amount!!) }, enabled = amount != null && amount > 0) { Text("Salvar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun InvoiceSummary(countsInLimit: Double, outsideLimit: Double) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(Modifier.weight(1f), color = CardSurfaceElevated, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(15.dp)) { Text("CONTA NO TETO", color = Emerald, fontSize = 10.sp, fontWeight = FontWeight.Bold); Text(brl.format(countsInLimit), fontWeight = FontWeight.Bold, fontSize = 18.sp) }
        }
        Surface(Modifier.weight(1f), color = Color(0xFF302F1C), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(15.dp)) { Text("FORA DO TETO", color = Color(0xFF8B5E23), fontSize = 10.sp, fontWeight = FontWeight.Bold); Text(brl.format(outsideLimit), fontWeight = FontWeight.Bold, fontSize = 18.sp) }
        }
    }
}

@Composable
private fun InvoiceSection(title: String, total: Double, purchases: List<CardPurchase>, accent: Color = Ink, onEdit: ((String) -> Unit)? = null) {
    Surface(modifier = Modifier.fillMaxWidth(), color = CardSurface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, DividerColor)) {
        Column(Modifier.padding(18.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(title, fontWeight = FontWeight.Bold, color = accent); Text(brl.format(total), fontWeight = FontWeight.Bold, color = accent) }
            purchases.forEachIndexed { index, purchase ->
                if (index > 0) HorizontalDivider(color = Color(0xFFE9EEEB))
                Row(Modifier.fillMaxWidth().padding(top = if (index == 0) 0.dp else 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(purchase.title, fontWeight = FontWeight.SemiBold); Text(purchase.subtitle, color = Color.Gray, fontSize = 11.sp) }
                    Text(brl.format(purchase.value), fontWeight = FontWeight.Bold)
                    if (purchase.transactionId != null && onEdit != null) {
                        IconButton(onClick = { onEdit(purchase.transactionId) }) { Icon(Icons.Outlined.Edit, "Editar compra", tint = Accent) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditCreditPurchaseDialog(purchase: CardPurchase, onDismiss: () -> Unit, onSave: (String, Double, Int, Boolean) -> Unit) {
    var description by remember(purchase.transactionId) { mutableStateOf(purchase.title) }
    var value by remember(purchase.transactionId) { mutableStateOf(purchase.value.toString().replace('.', ',')) }
    var installmentText by remember(purchase.transactionId) { mutableStateOf(purchase.installments.toString()) }
    var countsInLimit by remember(purchase.transactionId) { mutableStateOf(purchase.countsInLimit) }
    val total = value.replace(",", ".").toDoubleOrNull()
    val installments = installmentText.toIntOrNull()
    val valid = total != null && total > 0 && installments != null && installments in 1..48
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar compra") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(description, { description = it }, label = { Text("Descrição") }, singleLine = true)
                OutlinedTextField(value, { value = it.filter { c -> c.isDigit() || c == ',' || c == '.' } }, label = { Text("Valor total da compra") }, prefix = { Text("R$ ") }, singleLine = true)
                OutlinedTextField(installmentText, { installmentText = it.filter(Char::isDigit) }, label = { Text("Quantidade de parcelas") }, singleLine = true)
                Row(Modifier.fillMaxWidth().clickable { countsInLimit = !countsInLimit }, verticalAlignment = Alignment.CenterVertically) { Checkbox(countsInLimit, { countsInLimit = it }); Text("Conta no teto mensal") }
                if (valid) Text("${installments}x de ${brl.format(total!! / installments!!)}", color = Emerald, fontWeight = FontWeight.SemiBold)
                Text("Somente o valor da parcela cobrada nesta fatura conta no teto mensal. O restante será lançado nas próximas faturas.", color = SecondaryText, fontSize = 11.sp)
            }
        },
        confirmButton = { Button(onClick = { onSave(description.ifBlank { "Compra" }, total!!, installments!!, countsInLimit) }, enabled = valid) { Text("Salvar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun AccountEmptyState(icon: ImageVector, title: String, text: String) {
    Surface(modifier = Modifier.fillMaxWidth(), color = CardSurface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, DividerColor)) {
        Column(Modifier.fillMaxWidth().padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(58.dp).background(CardSurfaceElevated, CircleShape), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Accent) }
            Spacer(Modifier.height(14.dp)); Text(title, fontWeight = FontWeight.Bold, fontSize = 21.sp); Spacer(Modifier.height(7.dp)); Text(text, color = Color.Gray, lineHeight = 20.sp)
        }
    }
}

@Composable
private fun MonthsScreen(
    months: List<MonthSummary>, selectedIndex: Int, currentMonthIndex: Int, recurringItems: List<RecurringItem>,
    capturedTransactions: List<CapturedTransaction>, accountBalances: AccountBalances, userConfig: UserConfig, onSelect: (Int) -> Unit
) {
    val selected = months[selectedIndex]
    val context = LocalContext.current
    var detailType by remember(selectedIndex) { mutableStateOf<String?>(null) }
    fun isKnownThroughMonth(transaction: CapturedTransaction, monthIndex: Int): Boolean {
        val transactionIndex = months.indexOfFirst { it.label == transaction.monthLabel() }
        return transactionIndex >= 0 && transactionIndex <= monthIndex
    }
    val registeredBalance = accountBalances.mercadoPago + accountBalances.cash + capturedTransactions
        .filter { (it.source == "Mercado Pago" || it.source == "Dinheiro") && isKnownThroughMonth(it, selectedIndex) }
        .sumOf { if (it.direction == "IN") it.amount else -it.amount }
    val isCurrentMonth = selectedIndex == currentMonthIndex
    val isFutureMonth = selectedIndex > currentMonthIndex
    val purchaseOverrides = PurchaseOverrideStore.load(context)
    fun invoiceItemsFor(index: Int): List<CardPurchase> {
        val target = months[index]
        val imported = capturedTransactions
            .filter { it.kind == "CREDIT" && it.direction == "OUT" && it.installmentNumberIn(target.label) != null }
            .map { transaction ->
                CardPurchase(
                    transaction.description,
                    "Parcela ${transaction.installmentNumberIn(target.label)}/${transaction.installments}",
                    transaction.amountDueIn(target.label),
                    transaction.countsInLimit ?: countsTowardCardLimit(transaction.description),
                    transaction.id,
                    transaction.installments
                )
            }
        val known = if (index > 0) {
            knownCardPurchases(months[index - 1], index - 1, userConfig, purchaseOverrides, capturedTransactions)
                .withoutAutomaticallyImportedDuplicates(imported)
        } else emptyList()
        return known + imported
    }
    fun accountExpensesFor(index: Int): List<CapturedTransaction> = capturedTransactions.filter {
        it.kind != "CREDIT" && it.direction == "OUT" && it.occursIn(months[index].label)
    }
    val fixedExpenseTotal = recurringItems.filterNot { it.income }.sumOf { it.amount }
    fun completeExpensesFor(index: Int): Double =
        fixedExpenseTotal + invoiceItemsFor(index).sumOf { it.value } + accountExpensesFor(index).sumOf { it.amount }
    fun plannedRemainderFor(index: Int): Double = months[index].income - completeExpensesFor(index) - months[index].reserve
    val selectedInvoiceTotal = invoiceItemsFor(selectedIndex).sumOf { it.value }
    val selectedCompleteExpenses = completeExpensesFor(selectedIndex)
    val selectedPlannedRemainder = plannedRemainderFor(selectedIndex)
    var selectedInvoiceCategory by remember(selectedIndex) { mutableStateOf<ExpenseCategory?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Planejamento por mês", fontWeight = FontWeight.Bold, fontSize = 25.sp)
        Text("Toque em um mês para ver compromissos, fatura e parcelas.", color = Color.Gray)
        months.forEachIndexed { index, item ->
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { onSelect(index) },
                color = if (selectedIndex == index) CardSurfaceElevated else CardSurface,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(if (selectedIndex == index) 1.5.dp else 1.dp, if (selectedIndex == index) Accent else DividerColor)
            ) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(43.dp).background(if (selectedIndex == index) Accent else CardSurfaceElevated, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.CalendarMonth, null, tint = if (selectedIndex == index) Background else Accent) }
                    Column(Modifier.padding(start = 14.dp).weight(1f)) {
                        Text(item.label, fontWeight = FontWeight.Bold)
                        Text(when { index < currentMonthIndex -> "MÊS ENCERRADO"; index == currentMonthIndex -> "MÊS ATUAL"; else -> "PLANEJADO" }, color = if (index == currentMonthIndex) Emerald else Color.Gray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (index == currentMonthIndex) "Veja o saldo registrado abaixo" else "Sobra planejada: ${brl.format(plannedRemainderFor(index))}",
                            color = if (selectedIndex == index) Accent else SecondaryText,
                            fontSize = 12.sp
                        )
                    }
                    Icon(if (selectedIndex == index) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight, null, tint = Accent)
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Surface(modifier = Modifier.fillMaxWidth(), color = CardSurfaceElevated, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, DividerColor)) {
            Column(Modifier.fillMaxWidth().padding(22.dp)) {
                Text(selected.label.uppercase(), color = Color(0xFFA7F3D7), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(
                    when { isCurrentMonth -> "Saldo registrado agora"; isFutureMonth -> "Sobra planejada"; else -> "Resultado do planejamento" },
                    color = Color.White.copy(alpha = .7f),
                    modifier = Modifier.padding(top = 16.dp)
                )
                Text(brl.format(if (isCurrentMonth) registeredBalance else selectedPlannedRemainder), color = Color.White, fontSize = 35.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (isCurrentMonth) "Mercado Pago + dinheiro, com as movimentações registradas. O saldo da conta Nubank não está incluído."
                    else if (isFutureMonth) "Estimativa: receitas previstas menos despesas e reserva."
                    else "Valor calculado com o planejamento registrado para este mês.",
                    color = Color.White.copy(alpha = .62f), fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 6.dp)
                )
                val commitment = if (selected.income > 0) (selectedCompleteExpenses + selected.reserve) / selected.income else 0.0
                Spacer(Modifier.height(16.dp)); LinearProgressIndicator(progress = { commitment.toFloat().coerceIn(0f, 1f) }, color = Color(0xFF72E3B9), trackColor = Color.White.copy(alpha = .15f), modifier = Modifier.fillMaxWidth().height(8.dp))
                Text("${(commitment * 100).toInt()}% da renda comprometida", color = Color.White.copy(alpha = .7f), fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MonthMetric("Receitas", selected.income, Emerald, Modifier.weight(1f)) { detailType = "Receitas" }
            MonthMetric("Despesas fixas", fixedExpenseTotal, Color(0xFFD9495B), Modifier.weight(1f)) { detailType = "Despesas" }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MonthMetric("Fatura", selectedInvoiceTotal, Violet, Modifier.weight(1f)) { detailType = "Fatura" }
            MonthMetric("Reserva", selected.reserve, Color(0xFF9B6B22), Modifier.weight(1f)) { detailType = "Reserva" }
        }
        Surface(modifier = Modifier.fillMaxWidth(), color = CardSurface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, DividerColor)) {
            Column(Modifier.padding(18.dp)) {
                Text("Parcelas deste mês", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(8.dp))
                selected.installments.forEachIndexed { index, installment ->
                    if (index > 0) HorizontalDivider(color = Color(0xFFE9EEEB))
                    Row(Modifier.fillMaxWidth().padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.ReceiptLong, null, tint = Violet); Text(installment, Modifier.padding(start = 12.dp), fontWeight = FontWeight.Medium)
                    }
                }
                if (selectedIndex > 1) Text("Roupas foi quitada em setembro e não aparece mais.", color = Emerald, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
    detailType?.let { type ->
        val accountExpenses = accountExpensesFor(selectedIndex)
        val completeInvoiceItems = invoiceItemsFor(selectedIndex)
        val invoiceTotal = completeInvoiceItems.sumOf { it.value }
        val invoiceCategories = run {
            fun details(items: List<CardPurchase>) = items.map { ExpenseDetail(it.title, it.subtitle, it.value) }
            val subscriptions = completeInvoiceItems.filter { !countsTowardCardLimit(it.title) }
            val transport = completeInvoiceItems.filter { it !in subscriptions && (it.title.contains("uber", true) || it.title.contains("99", true)) }
            val food = completeInvoiceItems.filter { it !in subscriptions && it !in transport && (it.title.contains("kalzone", true) || it.title.contains("restaurante", true) || it.title.contains("atacadista", true) || (it.title.contains("mercado", true) && !it.title.contains("mercadolivre", true))) }
            val shopping = completeInvoiceItems.filterNot { it in subscriptions || it in transport || it in food }
            listOf(
                ExpenseCategory("Compras", Icons.Outlined.ShoppingBag, details(shopping)),
                ExpenseCategory("Alimentação", Icons.Outlined.Restaurant, details(food)),
                ExpenseCategory("Transporte", Icons.Outlined.DirectionsCar, details(transport)),
                ExpenseCategory("Assinaturas", Icons.Outlined.Subscriptions, details(subscriptions))
            ).filter { it.items.isNotEmpty() }
        }
        val details = when (type) {
            "Receitas" -> recurringItems.filter { it.income }.map { ExpenseDetail(it.name, "Previsto para dia ${it.dueDay}", it.amount) }
            "Despesas" -> recurringItems.filterNot { it.income }.map { ExpenseDetail(it.name, "Despesa fixa · vence dia ${it.dueDay}", it.amount) }
            else -> listOf(ExpenseDetail("Reserva planejada", "Meta do mês", selected.reserve))
        }
        if (type == "Fatura") {
            InvoiceCategoriesDialog(selected.label, invoiceCategories, invoiceTotal, onDismiss = { detailType = null }) { category ->
                detailType = null
                selectedInvoiceCategory = category
            }
        } else {
            MonthlyDetailsDialog("$type · ${selected.label}", details, when (type) { "Receitas" -> selected.income; "Despesas" -> fixedExpenseTotal; else -> selected.reserve }, onDismiss = { detailType = null })
        }
    }
    selectedInvoiceCategory?.let { category -> CategoryDetailsDialog(category, onDismiss = { selectedInvoiceCategory = null }) }
}

@Composable
private fun MonthMetric(title: String, value: Double, color: Color, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Surface(modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier), color = CardSurface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, DividerColor)) {
        Column(Modifier.padding(15.dp)) { Text(title, color = Color.Gray, fontSize = 11.sp); Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(brl.format(value), color = color, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(top = 5.dp).weight(1f)); if (onClick != null) Icon(Icons.Outlined.ChevronRight, "Ver detalhes", tint = color, modifier = Modifier.size(18.dp)) } }
    }
}

@Composable
private fun MonthlyDetailsDialog(title: String, details: List<ExpenseDetail>, total: Double, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column(Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState())) {
            if (details.isEmpty()) Text("Nenhum item cadastrado.", color = SecondaryText)
            details.forEachIndexed { index, item -> if (index > 0) HorizontalDivider(color = DividerColor); Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(item.title, fontWeight = FontWeight.SemiBold); Text(item.subtitle, color = SecondaryText, fontSize = 11.sp) }; Text(brl.format(item.value), fontWeight = FontWeight.Bold) } }
            HorizontalDivider(color = DividerColor); Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text("Total", fontWeight = FontWeight.Bold); Text(brl.format(total), fontWeight = FontWeight.Bold, color = Accent) }
        } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } }
    )
}

@Composable
private fun InvoiceCategoriesDialog(month: String, categories: List<ExpenseCategory>, total: Double, onDismiss: () -> Unit, onCategory: (ExpenseCategory) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fatura · $month") },
        text = {
            Column(Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState())) {
                if (categories.isEmpty()) Text("Nenhuma compra nesta fatura.", color = SecondaryText)
                categories.forEachIndexed { index, category ->
                    if (index > 0) HorizontalDivider(color = DividerColor)
                    Row(
                        Modifier.fillMaxWidth().clickable { onCategory(category) }.padding(vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(38.dp).background(CardSurfaceElevated, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) { Icon(category.icon, null, tint = Accent) }
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(category.title, fontWeight = FontWeight.SemiBold)
                            Text("${category.items.size} transação(ões)", color = SecondaryText, fontSize = 11.sp)
                        }
                        Text(brl.format(category.total), fontWeight = FontWeight.Bold)
                        Icon(Icons.Outlined.ChevronRight, "Ver transações", tint = SecondaryText, modifier = Modifier.padding(start = 5.dp).size(18.dp))
                    }
                }
                HorizontalDivider(color = DividerColor)
                Row(Modifier.fillMaxWidth().padding(top = 13.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Total da fatura", fontWeight = FontWeight.Bold)
                    Text(brl.format(total), fontWeight = FontWeight.Bold, color = Accent)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } }
    )
}

@Composable
private fun AssistantScreen(userConfig: UserConfig, recurringItems: List<RecurringItem>, capturedTransactions: List<CapturedTransaction>) {
    val context = LocalContext.current
    var question by remember { mutableStateOf("") }
    val calendar = remember { Calendar.getInstance() }
    val monthNames = listOf("Janeiro", "Fevereiro", "Março", "Abril", "Maio", "Junho", "Julho", "Agosto", "Setembro", "Outubro", "Novembro", "Dezembro")
    val currentMonth = "${monthNames[calendar.get(Calendar.MONTH)]} de ${calendar.get(Calendar.YEAR)}"
    val nextCalendar = (calendar.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
    val nextMonth = "${monthNames[nextCalendar.get(Calendar.MONTH)]} de ${nextCalendar.get(Calendar.YEAR)}"
    val currentIndex = appMonths.indexOfFirst { it.label == currentMonth }.coerceAtLeast(0)
    val currentTransactions = capturedTransactions.filter { it.occursIn(currentMonth) }
    val nextMonthInvoice = (if (currentMonth == "Agosto de 2026") userConfig.knownInvoice else 0.0) + capturedTransactions.filter { it.kind == "CREDIT" && it.direction == "OUT" }.sumOf { it.amountDueIn(nextMonth) }
    val nextMonthAccountExpenses = capturedTransactions.filter { it.kind != "CREDIT" && it.direction == "OUT" && it.occursIn(nextMonth) }.sumOf { it.amount }
    val cycleCharges = capturedTransactions.filter { it.kind == "CREDIT" && it.direction == "OUT" && it.installmentNumberInInvoiceCycle(currentMonth) != null }.map {
        CardPurchase(it.description, "Cobrança", it.amount / it.installments.coerceAtLeast(1), it.countsInLimit ?: countsTowardCardLimit(it.description), it.id, it.installments)
    }
    val knownCharges = knownCardPurchases(appMonths.getOrElse(currentIndex) { appMonths.first() }, currentIndex, userConfig, PurchaseOverrideStore.load(context), capturedTransactions).withoutAutomaticallyImportedDuplicates(cycleCharges)
    val recurringIncome = recurringItems.filter { it.income }.sumOf { it.amount }
    val recurringExpenses = recurringItems.filterNot { it.income }.sumOf { it.amount }
    val projectedRemaining = recurringIncome - recurringExpenses - userConfig.reserveGoal - nextMonthInvoice - nextMonthAccountExpenses
    val invoice = nextMonthInvoice
    val limitSpent = (knownCharges + cycleCharges).filter { it.countsInLimit }.sumOf { it.value }
    var answer by remember { mutableStateOf("Oi, ${userConfig.name}! Posso calcular sua sobra, explicar a fatura e acompanhar seu teto.") }
    fun respond(text: String) {
        answer = when {
            text.contains("sobr", true) || text.contains("mês que vem", true) -> "Com os valores configurados e os gastos capturados, a projeção é sobrar ${brl.format(projectedRemaining)}."
            text.contains("teto", true) || text.contains("gastar", true) -> "Você usou ${brl.format(limitSpent)} do teto de ${brl.format(userConfig.cardBudget)}. Ainda pode gastar ${brl.format(userConfig.cardBudget - limitSpent)}."
            text.contains("fatura", true) -> "Sua fatura conhecida está em ${brl.format(invoice)}."
            else -> "Pergunte quanto sobrará no próximo mês, quanto ainda pode gastar ou qual é a fatura conhecida."
        }
        question = ""
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(modifier = Modifier.fillMaxWidth(), color = CardSurfaceElevated, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, DividerColor)) { Column(Modifier.fillMaxWidth().padding(22.dp)) { Icon(Icons.Outlined.SmartToy, null, tint = Accent); Spacer(Modifier.height(12.dp)); Text("Assistente Cifra", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold); Text("Pergunte. O Cifra faz as contas.", color = Color.White.copy(alpha = .7f)) } }
        Surface(modifier = Modifier.fillMaxWidth(), color = CardSurface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, DividerColor)) { Text(answer, Modifier.padding(18.dp), lineHeight = 22.sp) }
        listOf("Quanto sobrará mês que vem?", "Quanto ainda posso gastar?", "Qual é minha fatura?").forEach { suggestion -> AssistChip(onClick = { respond(suggestion) }, label = { Text(suggestion) }) }
        Spacer(Modifier.weight(1f))
        OutlinedTextField(value = question, onValueChange = { question = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Pergunte sobre seu dinheiro…") }, trailingIcon = { IconButton(onClick = { respond(question) }) { Icon(Icons.Outlined.Send, "Enviar", tint = Accent) } }, shape = RoundedCornerShape(16.dp))
        Text("Cálculos feitos no aparelho, sem API paga.", color = Color.Gray, fontSize = 10.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}
