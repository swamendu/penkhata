package com.example.penkhata

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

const val APP_VERSION = "1.8 \u00b7 iteration 5"

data class InvItem(val desc: String, val serial: String, val hsn: String, val qty: Double, val rate: Double, val unit: String, val taxRate: Double, val discount: Double = 0.0)
val InvItemListSaver: Saver<List<InvItem>, String> = Saver(
    save = { list -> list.joinToString("\u0002") { "${it.desc}\u0001${it.serial}\u0001${it.hsn}\u0001${it.qty}\u0001${it.rate}\u0001${it.unit}\u0001${it.taxRate}\u0001${it.discount}" } },
    restore = { s ->
        if(s.isEmpty()) emptyList() else s.split("\u0002").mapNotNull { row ->
            val f = row.split("\u0001")
            if(f.size < 7) null else InvItem(f[0], f[1], f[2], f[3].toDoubleOrNull() ?: 0.0, f[4].toDoubleOrNull() ?: 0.0, f[5], f[6].toDoubleOrNull() ?: 18.0, if(f.size > 7) f[7].toDoubleOrNull() ?: 0.0 else 0.0)
        }
    }
)
data class StockEntry(val id: Long, val model: String, val imei: String, val hsn: String,
                      val cost: Double, val mrp: Double, val qty: Int, val supplier: String, val addedOn: String)
data class LedgerEntry(val id: Long, val date: String, val party: String, val type: String, val amount: Double, val desc: String)

data class BillCalc(
    val gross: Double, val itemDiscount: Double, val billDiscount: Double,
    val taxable: Double, val cgst: Double, val sgst: Double, val igst: Double, val totalTax: Double,
    val roundOff: Double, val grand: Double, val exchange: Double,
    val cash: Double, val upi: Double, val card: Double, val paid: Double, val balance: Double,
    val interState: Boolean
)

// Single place where a bill is added up, so the screen and the PDF can never disagree.
// Rates are tax-inclusive: each line is split into taxable value and tax, item discount is
// applied before the split, and IGST replaces CGST+SGST when the buyer's state differs.
fun computeBill(items: List<InvItem>, billDiscount: Double, exchange: Double, cash: Double, upi: Double, card: Double,
                sellerState: String, buyerState: String): BillCalc {
    var gross = 0.0; var itemDisc = 0.0; var taxable = 0.0; var tax = 0.0
    for(it in items) {
        val g = it.qty * it.rate
        val d = g * (it.discount.coerceIn(0.0, 100.0) / 100.0)
        val net = g - d
        val tr = it.taxRate.coerceIn(0.0, 100.0)
        val base = net / (1 + tr / 100.0)
        gross += g; itemDisc += d; taxable += base; tax += (net - base)
    }
    val bd = billDiscount.coerceIn(0.0, taxable + tax)
    // The bill discount is spread over taxable value and tax in proportion, so the
    // printed rows add up: Gross - Discount = Taxable Value + CGST + SGST.
    val netBeforeBillDisc = taxable + tax
    val keepFactor = if(netBeforeBillDisc > 0.0) (netBeforeBillDisc - bd) / netBeforeBillDisc else 0.0
    val taxableAfter = taxable * keepFactor
    val taxAfter = tax * keepFactor
    val beforeRound = taxableAfter + taxAfter
    val rounded = Math.round(beforeRound).toDouble()
    val roundOff = rounded - beforeRound
    val paid = cash + upi + card
    val interState = sellerState.isNotBlank() && buyerState.isNotBlank() && sellerState.trim() != buyerState.trim()
    val grand = rounded - exchange
    return BillCalc(gross, itemDisc, bd, taxableAfter,
        if(interState) 0.0 else taxAfter / 2, if(interState) 0.0 else taxAfter / 2, if(interState) taxAfter else 0.0, taxAfter,
        roundOff, grand, exchange, cash, upi, card, paid, grand - paid, interState)
}

fun fmt(v: Double): String = String.format("%.2f", v)

// Indian financial year label, e.g. "25-26" for bills dated after 1 April 2025.
fun fyLabel(): String {
    val c = Calendar.getInstance()
    val start = if(c.get(Calendar.MONTH) + 1 >= 4) c.get(Calendar.YEAR) else c.get(Calendar.YEAR) - 1
    return String.format("%02d-%02d", start % 100, (start + 1) % 100)
}

fun nextInvoiceNo(prefs: android.content.SharedPreferences): String {
    val prefix = (prefs.getString("sPrefix", "") ?: "").trim().ifEmpty { "INV" }
    val n = prefs.getInt("nextInv", 1)
    return "$prefix/${fyLabel()}/" + String.format("%03d", n)
}

fun bumpInvoiceNo(prefs: android.content.SharedPreferences) {
    prefs.edit().putInt("nextInv", prefs.getInt("nextInv", 1) + 1).apply()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF2E7D32))) { MainAppScreen() } }
    }
}

@Composable
fun MainAppScreen() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("penkhata_data", Context.MODE_PRIVATE) }
    var savedPin by remember { mutableStateOf(prefs.getString("appPin", "") ?: "") }
    var isLocked by remember { mutableStateOf(savedPin.isNotEmpty()) }
    var currentScreen by remember { mutableStateOf("dashboard") }

    if (isLocked) LoginScreen(savedPin) { isLocked = false }
    else Scaffold(
        bottomBar = { NavigationBar {
            NavigationBarItem(selected = currentScreen=="dashboard", onClick = { currentScreen="dashboard" }, icon = { Icon(Icons.Default.EditNote, "Bill") }, label = { Text("Entry") })
            NavigationBarItem(selected = currentScreen=="ledger", onClick = { currentScreen="ledger" }, icon = { Icon(Icons.Default.AccountBalanceWallet, "Ledger") }, label = { Text("Ledger") })
            NavigationBarItem(selected = currentScreen=="stock", onClick = { currentScreen="stock" }, icon = { Icon(Icons.Default.Inventory, "Stock") }, label = { Text("Stock") })
            NavigationBarItem(selected = currentScreen=="reports", onClick = { currentScreen="reports" }, icon = { Icon(Icons.Default.Assessment, "GST") }, label = { Text("GST") })
            NavigationBarItem(selected = currentScreen=="history", onClick = { currentScreen="history" }, icon = { Icon(Icons.Default.History, "Files") }, label = { Text("Files") })
            NavigationBarItem(selected = currentScreen=="settings", onClick = { currentScreen="settings" }, icon = { Icon(Icons.Default.Settings, "Set") }, label = { Text("Set") })
        }}
    ) { p -> Box(Modifier.padding(p)) { when(currentScreen) {
        "dashboard" -> InvoiceScreen(false)
        "ledger" -> LedgerScreen()
        "stock" -> StockScreen()
        "reports" -> GSTReportScreen()
        "history" -> FileHistoryScreen()
        else -> SettingsScreen(savedPin) { new -> prefs.edit().putString("appPin", new).apply(); savedPin = new }
    }}}
}

@Composable
fun LoginScreen(pin: String, unlock: () -> Unit) {
    var input by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("LOCKED", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(value=input, onValueChange={input=it}, visualTransformation=PasswordVisualTransformation(), keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword), modifier=Modifier.padding(20.dp))
        Button(onClick={if(input==pin) unlock()}) { Text("UNLOCK") }
    }
}

@Composable
fun InvoiceScreen(isQuote: Boolean) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("penkhata_data", android.content.Context.MODE_PRIVATE) }
    val sellerState = prefs.getString("sState", "21") ?: "21"
    var invNo by rememberSaveable { mutableStateOf(nextInvoiceNo(prefs)) }
    var date by rememberSaveable { mutableStateOf(SimpleDateFormat("dd-MMM-yyyy", Locale.ENGLISH).format(Date())) }
    var payMode by rememberSaveable { mutableStateOf("") }; var delNote by rememberSaveable { mutableStateOf("") }
    var bName by rememberSaveable { mutableStateOf("") }
    var bAddr1 by rememberSaveable { mutableStateOf("") }; var bAddr2 by rememberSaveable { mutableStateOf("") }
    var bLandmark by rememberSaveable { mutableStateOf("") }; var bPincode by rememberSaveable { mutableStateOf("") }
    var bPhone by rememberSaveable { mutableStateOf("") }
    var bGst by rememberSaveable { mutableStateOf("") }; var bState by rememberSaveable { mutableStateOf("") }
    var iDesc by rememberSaveable { mutableStateOf("") }; var iSerial by rememberSaveable { mutableStateOf("") }
    var iHsn by rememberSaveable { mutableStateOf("") }; var iQty by rememberSaveable { mutableStateOf("1") }
    var iRate by rememberSaveable { mutableStateOf("") }; var iUnit by rememberSaveable { mutableStateOf("pcs") }
    var iTax by rememberSaveable { mutableStateOf(prefs.getString("defTax", "18") ?: "18") }
    var iDisc by rememberSaveable { mutableStateOf("0") }
    var items by rememberSaveable(stateSaver = InvItemListSaver) { mutableStateOf(listOf<InvItem>()) }
    var billDisc by rememberSaveable { mutableStateOf("") }; var exchange by rememberSaveable { mutableStateOf("") }
    var cash by rememberSaveable { mutableStateOf("") }; var upi by rememberSaveable { mutableStateOf("") }
    var card by rememberSaveable { mutableStateOf("") }; var finance by rememberSaveable { mutableStateOf("") }

    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    fun clearBill() {
        invNo = nextInvoiceNo(prefs)
        bName=""; bAddr1=""; bAddr2=""; bLandmark=""; bPincode=""; bPhone=""; bGst=""; bState=""
        items = emptyList()
        billDisc=""; exchange=""; cash=""; upi=""; card=""; finance=""
        payMode=""; delNote=""
    }

    // Pay Mode follows the Cash / UPI / Card amounts, so it never contradicts them.
    val autoPayMode = listOf(
        if((cash.toDoubleOrNull() ?: 0.0) > 0.0) "CASH" else "",
        if((upi.toDoubleOrNull() ?: 0.0) > 0.0) "UPI" else "",
        if((card.toDoubleOrNull() ?: 0.0) > 0.0) "CARD" else ""
    ).filter{it.isNotBlank()}.joinToString(", ")
    LaunchedEffect(autoPayMode) { if(autoPayMode.isNotBlank() && payMode != autoPayMode) payMode = autoPayMode }

    val calc = computeBill(items, billDisc.toDoubleOrNull() ?: 0.0, exchange.toDoubleOrNull() ?: 0.0,
        cash.toDoubleOrNull() ?: 0.0, upi.toDoubleOrNull() ?: 0.0, card.toDoubleOrNull() ?: 0.0, sellerState, bState)

    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(if(isQuote) "QUOTATION" else "TAX INVOICE", fontSize = 24.sp, fontWeight = FontWeight.Bold, color=Color.Blue)
            TextButton(onClick = { clearBill(); Toast.makeText(ctx, "Cleared", Toast.LENGTH_SHORT).show() }) { Text("RESET", color = Color.Red) }
        }
        Card(Modifier.padding(vertical=5.dp)) { Column(Modifier.padding(10.dp)) {
            Row { OutlinedTextField(invNo, {invNo=it}, label={Text("Inv No")}, modifier=Modifier.weight(1f)); Spacer(Modifier.width(5.dp)); OutlinedTextField(date, {date=it}, label={Text("Date")}, modifier=Modifier.weight(1f)) }
            Row { OutlinedTextField(payMode, {payMode=it}, label={Text("Pay Mode")}, modifier=Modifier.weight(1f)); Spacer(Modifier.width(5.dp)); OutlinedTextField(delNote, {delNote=it}, label={Text("Delivery Note")}, modifier=Modifier.weight(1f)) }
            Text(if(autoPayMode.isBlank()) "Pay Mode fills itself from Cash / UPI / Card below" else "Pay Mode: $autoPayMode", fontSize=10.sp, color=Color(0xFF2E7D32))
        }}
        Card(Modifier.padding(vertical=5.dp)) { Column(Modifier.padding(10.dp)) {
            Text("Buyer Details", fontWeight=FontWeight.Bold)
            OutlinedTextField(bName, {bName=it}, label={Text("Name")}, modifier=Modifier.fillMaxWidth())
            OutlinedTextField(bAddr1, {bAddr1=it}, label={Text("Address Line 1")}, modifier=Modifier.fillMaxWidth())
            OutlinedTextField(bAddr2, {bAddr2=it}, label={Text("Address Line 2")}, modifier=Modifier.fillMaxWidth())
            OutlinedTextField(bLandmark, {bLandmark=it}, label={Text("Landmark")}, modifier=Modifier.fillMaxWidth())
            OutlinedTextField(bPincode, {bPincode=it}, label={Text("Pincode")}, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number), modifier=Modifier.fillMaxWidth())
            OutlinedTextField(bPhone, {bPhone=it}, label={Text("Phone")}, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Phone), modifier=Modifier.fillMaxWidth())
            Row { OutlinedTextField(bGst, {bGst=it}, label={Text("GSTIN")}, modifier=Modifier.weight(1f)); Spacer(Modifier.width(5.dp)); OutlinedTextField(bState, {bState=it}, label={Text("State Code")}, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number), modifier=Modifier.weight(1f)) }
            if(calc.interState) Text("Other state: IGST will be charged", fontSize=10.sp, color=Color(0xFF2E7D32))
        }}
        Card(Modifier.padding(vertical=5.dp)) { Column(Modifier.padding(10.dp)) {
            Text("Add Item", fontWeight=FontWeight.Bold)
            OutlinedTextField(iDesc, {iDesc=it}, label={Text("Item Name")}, modifier=Modifier.fillMaxWidth())
            OutlinedTextField(iSerial, {iSerial=it}, label={Text("Serial / IMEI")}, modifier=Modifier.fillMaxWidth())
            Row { OutlinedTextField(iHsn, {iHsn=it}, label={Text("HSN")}, modifier=Modifier.weight(1f)); Spacer(Modifier.width(5.dp)); OutlinedTextField(iUnit, {iUnit=it}, label={Text("Unit")}, modifier=Modifier.weight(1f)) }
            Row { OutlinedTextField(iQty, {iQty=it}, label={Text("Qty")}, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number), modifier=Modifier.weight(1f)); Spacer(Modifier.width(5.dp)); OutlinedTextField(iRate, {iRate=it}, label={Text("Rate (Inc. Tax)")}, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number), modifier=Modifier.weight(1f)) }
            Row(Modifier.padding(top=5.dp)) { OutlinedTextField(iTax, {iTax=it}, label={Text("GST %")}, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number), modifier=Modifier.weight(1f)); Spacer(Modifier.width(5.dp)); OutlinedTextField(iDisc, {iDisc=it}, label={Text("Disc %")}, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number), modifier=Modifier.weight(1f)) }
            Button(onClick={
                if(iDesc.isNotEmpty()) {
                    items = items + InvItem(iDesc, iSerial, iHsn, iQty.toDoubleOrNull() ?: 1.0, iRate.toDoubleOrNull() ?: 0.0, iUnit,
                        (iTax.toDoubleOrNull() ?: 18.0).coerceIn(0.0, 100.0), (iDisc.toDoubleOrNull() ?: 0.0).coerceIn(0.0, 100.0))
                    iDesc=""; iSerial=""; iQty="1"; iRate=""; iDisc="0"
                }
            }, modifier=Modifier.fillMaxWidth().padding(top=5.dp)) { Text("ADD") }
        }}
        items.forEachIndexed{ idx, it ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("${idx+1}. ${it.desc} (${qtyText(it.qty)} ${it.unit}) \u20b9${fmt(it.rate)}" + (if(it.discount>0.004) " -${fmt(it.discount)}%" else ""), Modifier.weight(1f))
                IconButton(onClick={ val m=items.toMutableList(); m.removeAt(idx); items=m }) { Icon(Icons.Default.Delete,"Del",tint=Color.Red) }
            }
            Divider()
        }
        Card(Modifier.padding(vertical=5.dp)) { Column(Modifier.padding(10.dp)) {
            Text("Charges & Payment", fontWeight=FontWeight.Bold)
            Row { OutlinedTextField(billDisc, {billDisc=it}, label={Text("Bill Discount \u20b9")}, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number), modifier=Modifier.weight(1f)); Spacer(Modifier.width(5.dp)); OutlinedTextField(exchange, {exchange=it}, label={Text("Exchange \u20b9")}, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number), modifier=Modifier.weight(1f)) }
            Row { OutlinedTextField(cash, {cash=it}, label={Text("Cash \u20b9")}, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number), modifier=Modifier.weight(1f)); Spacer(Modifier.width(5.dp)); OutlinedTextField(upi, {upi=it}, label={Text("UPI \u20b9")}, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number), modifier=Modifier.weight(1f)); Spacer(Modifier.width(5.dp)); OutlinedTextField(card, {card=it}, label={Text("Card \u20b9")}, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number), modifier=Modifier.weight(1f)) }
            OutlinedTextField(finance, {finance=it}, label={Text("Finance / EMI Scheme")}, modifier=Modifier.fillMaxWidth())
        }}
        Card(Modifier.padding(vertical=5.dp)) { Column(Modifier.padding(10.dp)) {
            Text("Summary", fontWeight=FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("Gross Value"); Text("\u20b9${fmt(calc.gross)}") }
            val shownDiscount = calc.itemDiscount + calc.billDiscount
            if(shownDiscount>0.004) Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("Less Discount"); Text("-\u20b9${fmt(shownDiscount)}") }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("Taxable Value"); Text("\u20b9${fmt(calc.taxable)}") }
            if(calc.interState) Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("IGST"); Text("\u20b9${fmt(calc.igst)}") }
            else { Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("CGST"); Text("\u20b9${fmt(calc.cgst)}") }; Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("SGST"); Text("\u20b9${fmt(calc.sgst)}") } }
            if(Math.abs(calc.roundOff)>=0.005) Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("Round Off"); Text("\u20b9${fmt(calc.roundOff)}") }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("Grand Total", fontWeight=FontWeight.Bold); Text("\u20b9${fmt(calc.grand)}", fontWeight=FontWeight.Bold) }
            if(calc.paid>0.004) Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("Paid"); Text("\u20b9${fmt(calc.paid)}") }
            if(Math.abs(calc.balance)>0.004) Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("Balance Due", color=Color.Red); Text("\u20b9${fmt(calc.balance)}", color=Color.Red) }
        }}
        Spacer(Modifier.height(10.dp))
        Button(enabled = !busy, onClick={
            val sName = prefs.getString("sName", "") ?: ""
            if(sName.isEmpty()) Toast.makeText(ctx, "Setup Settings First!", Toast.LENGTH_SHORT).show()
            else if(items.isEmpty()) Toast.makeText(ctx, "Add at least one item", Toast.LENGTH_SHORT).show()
            else {
                // Snapshot everything now: the form is cleared once the PDF is written.
                val snapInv = invNo; val snapDate = date; val snapPay = payMode; val snapNote = delNote
                val snapItems = items; val snapCalc = calc
                val snapBuyer = listOf(bName, bAddr1, bAddr2, bLandmark, bPincode, bPhone, bGst, bState)
                val snapFinance = finance
                val seller = listOf(
                    sName,
                    listOf(prefs.getString("sAddr1","")?:"", prefs.getString("sAddr2","")?:"").filter{it.isNotBlank()}.joinToString("\n"),
                    prefs.getString("sPhone","")?:"", prefs.getString("sEmail","")?:"", prefs.getString("sLandmark","")?:"", prefs.getString("sPincode","")?:"",
                    prefs.getString("sGst","")?:"", prefs.getString("sBank","")?:"", prefs.getString("sIfsc","")?:"", prefs.getString("sJuris","")?:"" )
                busy = true
                scope.launch {
                    val file = try {
                        withContext(Dispatchers.IO) {
                            if(!isQuote) saveInvoiceData(ctx, snapInv, snapBuyer[0], snapBuyer[6], snapCalc, snapItems)
                            createPdf(ctx, isQuote, snapInv, snapDate, snapPay, snapNote, seller[0], seller[1], seller[2], seller[3], seller[4], seller[5],
                                seller[6], seller[7], seller[8], seller[9], snapBuyer[0], snapBuyer[1], snapBuyer[2], snapBuyer[3],
                                snapBuyer[4], snapBuyer[5], snapBuyer[6], snapBuyer[7], snapItems, snapCalc, snapFinance)
                        }
                    } catch(e: Exception) {
                        null
                    }
                    busy = false
                    if(file == null) { Toast.makeText(ctx, "Could not write the PDF", Toast.LENGTH_LONG).show() }
                    else {
                        if(!isQuote) {
                            bumpInvoiceNo(prefs); clearBill()
                            scope.launch { withContext(Dispatchers.IO) { deductStock(ctx, snapItems) } }
                        }
                        openPdf(ctx, file)
                    }
                }
            }
        }, modifier=Modifier.fillMaxWidth(), colors=ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))) { Text(if(busy) "GENERATING..." else if(isQuote) "GENERATE QUOTE" else "GENERATE PDF") }
        Spacer(Modifier.height(60.dp))
    }
}

// --- SECONDARY SCREENS ---
@Composable fun LedgerScreen() {
    val ctx = LocalContext.current
    var p by rememberSaveable { mutableStateOf("") }; var t by rememberSaveable { mutableStateOf("DEBIT") }
    var a by rememberSaveable { mutableStateOf("") }; var d by rememberSaveable { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }
    val entries by produceState(initialValue = emptyList<LedgerEntry>(), reload) {
        value = withContext(Dispatchers.IO) { loadLedger(ctx) }
    }
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("LEDGER", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Card(Modifier.padding(vertical = 10.dp)) { Column(Modifier.padding(10.dp)) {
            OutlinedTextField(p, {p=it}, label={Text("Party")}, modifier=Modifier.fillMaxWidth())
            Row {
                Button(onClick={t="DEBIT"}, colors=ButtonDefaults.buttonColors(containerColor=if(t=="DEBIT")Color.Red else Color.Gray), modifier=Modifier.weight(1f)){Text("DEBIT")}
                Spacer(Modifier.width(5.dp))
                Button(onClick={t="CREDIT"}, colors=ButtonDefaults.buttonColors(containerColor=if(t=="CREDIT")Color.Green else Color.Gray), modifier=Modifier.weight(1f)){Text("CREDIT")}
            }
            OutlinedTextField(a, {a=it}, label={Text("Amount")}, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number), modifier=Modifier.fillMaxWidth())
            OutlinedTextField(d, {d=it}, label={Text("Note")}, modifier=Modifier.fillMaxWidth())
            Button(onClick={
                if(p.isNotEmpty() && a.isNotEmpty()) {
                    val en = LedgerEntry(System.currentTimeMillis(), SimpleDateFormat("dd-MM", Locale.ENGLISH).format(Date()), p, t, a.toDoubleOrNull() ?: 0.0, d)
                    saveLedgerEntry(ctx, en); reload++; p=""; a=""
                }
            }, modifier=Modifier.fillMaxWidth()) { Text("ADD") }
        }}
        entries.reversed().forEach {
            Row(Modifier.padding(8.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Text(it.party, fontWeight=FontWeight.Bold); Text(it.desc, fontSize=10.sp) }
                Column(horizontalAlignment = Alignment.End) {
                    Text(it.type, color=if(it.type=="DEBIT") Color.Red else Color.Green, fontSize=10.sp)
                    Text("\u20b9${fmt(it.amount)}", fontWeight=FontWeight.Bold)
                }
            }
            Divider()
        }
        Spacer(Modifier.height(50.dp))
    }
}
@Composable fun StockScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableStateOf(0) }
    val stock by produceState(initialValue = emptyList<StockEntry>(), reload) {
        value = withContext(Dispatchers.IO) { loadStock(ctx) }
    }
    var model by rememberSaveable { mutableStateOf("") }; var imei by rememberSaveable { mutableStateOf("") }
    var hsn by rememberSaveable { mutableStateOf("") }; var cost by rememberSaveable { mutableStateOf("") }
    var mrp by rememberSaveable { mutableStateOf("") }; var qty by rememberSaveable { mutableStateOf("1") }
    var supplier by rememberSaveable { mutableStateOf("") }
    val totalUnits = stock.sumOf { it.qty }
    val totalValue = stock.sumOf { it.cost * it.qty }
    val lowCount = stock.count { it.qty <= 1 }
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("STOCK", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Card(Modifier.padding(vertical = 10.dp)) { Column(Modifier.padding(10.dp)) {
            Text("Add Stock", fontWeight = FontWeight.Bold)
            OutlinedTextField(model, {model=it}, label={Text("Model / Item Name")}, modifier=Modifier.fillMaxWidth())
            OutlinedTextField(imei, {imei=it}, label={Text("IMEI / Serial (optional)")}, modifier=Modifier.fillMaxWidth())
            Row { OutlinedTextField(hsn, {hsn=it}, label={Text("HSN")}, modifier=Modifier.weight(1f)); Spacer(Modifier.width(5.dp)); OutlinedTextField(qty, {qty=it}, label={Text("Qty")}, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number), modifier=Modifier.weight(1f)) }
            Row { OutlinedTextField(cost, {cost=it}, label={Text("Purchase \u20b9")}, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number), modifier=Modifier.weight(1f)); Spacer(Modifier.width(5.dp)); OutlinedTextField(mrp, {mrp=it}, label={Text("MRP \u20b9")}, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number), modifier=Modifier.weight(1f)) }
            OutlinedTextField(supplier, {supplier=it}, label={Text("Supplier")}, modifier=Modifier.fillMaxWidth())
            Button(onClick = {
                if(model.isNotBlank()) {
                    val entry = StockEntry(System.currentTimeMillis(), model.trim(), imei.trim(), hsn.trim(),
                        cost.toDoubleOrNull() ?: 0.0, mrp.toDoubleOrNull() ?: 0.0, (qty.toIntOrNull() ?: 1).coerceAtLeast(0),
                        supplier.trim(), SimpleDateFormat("dd-MMM-yyyy", Locale.ENGLISH).format(Date()))
                    scope.launch {
                        withContext(Dispatchers.IO) { saveStock(ctx, loadStock(ctx) + entry) }
                        reload++
                        model=""; imei=""; hsn=""; cost=""; mrp=""; qty="1"; supplier=""
                        Toast.makeText(ctx, "Added to stock", Toast.LENGTH_SHORT).show()
                    }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("ADD TO STOCK") }
        }}
        Card(Modifier.padding(vertical = 10.dp)) { Column(Modifier.padding(16.dp)) {
            Text("Summary", fontWeight = FontWeight.Bold)
            Divider(Modifier.padding(vertical = 5.dp))
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("Models"); Text("${stock.size}") }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("Units in hand"); Text("$totalUnits") }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("Stock value (cost)"); Text("\u20b9${fmt(totalValue)}") }
            if(lowCount > 0) Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("Low stock (1 or less)", color = Color.Red); Text("$lowCount", color = Color.Red) }
        }}
        stock.sortedBy { it.model.lowercase() }.forEach { e ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(e.model, fontWeight = FontWeight.Bold)
                    if(e.imei.isNotBlank()) Text("IMEI: ${e.imei}", fontSize = 10.sp)
                    Text("Cost \u20b9${fmt(e.cost)}  MRP \u20b9${fmt(e.mrp)}" + (if(e.supplier.isBlank()) "" else "  \u00b7  ${e.supplier}"), fontSize = 10.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Qty ${e.qty}", color = if(e.qty <= 1) Color.Red else Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                    if(e.qty <= 1) Text("LOW", fontSize = 10.sp, color = Color.Red)
                }
                IconButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { saveStock(ctx, loadStock(ctx).filter { it.id != e.id }) }
                        reload++
                    }
                }) { Icon(Icons.Default.Delete, "Del", tint = Color.Red) }
            }
            Divider()
        }
        Spacer(Modifier.height(50.dp))
    }
}
@Composable fun GSTReportScreen() {
    val ctx = LocalContext.current
    // Reading every saved invoice on the main thread made this tab stutter; load on IO.
    val d by produceState(initialValue = emptyMap<String, String>(), ctx) {
        value = withContext(Dispatchers.IO) { loadGstData(ctx) }
    }
    Column(Modifier.padding(16.dp)) {
        Text("GST DASHBOARD", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Card(Modifier.padding(vertical = 10.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("Summary", fontWeight = FontWeight.Bold)
                Divider(Modifier.padding(vertical = 5.dp))
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("Sales:"); Text("\u20b9${d["total"] ?: "0.00"}") }
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("Tax:"); Text("\u20b9${d["tax"] ?: "0.00"}") }
                val sk = (d["skipped"] ?: "0").toIntOrNull() ?: 0
                if (sk > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text("$sk invoice file(s) could not be read and are left out of these totals.", fontSize = 10.sp, color = Color.Red)
                }
            }
        }
    }
}
@Composable fun FileHistoryScreen() { val ctx=LocalContext.current; val d=ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS); var f by remember{mutableStateOf(d?.listFiles()?.filter{it.extension=="pdf"}?.sortedByDescending{it.lastModified()}?:emptyList())}; LazyColumn(Modifier.padding(16.dp)){items(f){fl->Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f).clickable{ctx.startActivity(Intent(Intent.ACTION_VIEW).apply{setDataAndType(FileProvider.getUriForFile(ctx,"${ctx.packageName}.provider",fl),"application/pdf");addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)})}){Text(fl.name,fontWeight=FontWeight.Bold);Text(SimpleDateFormat("dd MMM HH:mm").format(Date(fl.lastModified())),fontSize=10.sp)}; IconButton(onClick={if(fl.delete())f=d?.listFiles()?.filter{it.extension=="pdf"}?.sortedByDescending{it.lastModified()}?:emptyList()}){Icon(Icons.Default.Delete,"Del",tint=Color.Red)}};Divider()}} }
@Composable fun SettingsScreen(currentPin: String, onPinSave: (String)->Unit) {
    val ctx=LocalContext.current
    val prefs=remember{ctx.getSharedPreferences("penkhata_data",Context.MODE_PRIVATE)}
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if(uri != null) {
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(backupJson(ctx).toByteArray()) }
                Toast.makeText(ctx, "Backup saved", Toast.LENGTH_LONG).show()
            } catch(e: Exception) {
                Toast.makeText(ctx, "Backup failed: " + (e.message ?: "unknown error"), Toast.LENGTH_LONG).show()
            }
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri != null) {
            try {
                val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                Toast.makeText(ctx, restoreJson(ctx, text), Toast.LENGTH_LONG).show()
            } catch(e: Exception) {
                Toast.makeText(ctx, "Restore failed: " + (e.message ?: "unknown error"), Toast.LENGTH_LONG).show()
            }
        }
    }
    var sName by remember{mutableStateOf(prefs.getString("sName","")?:"")}; var sState by remember{mutableStateOf(prefs.getString("sState","21")?:"21")}; var sPrefix by remember{mutableStateOf(prefs.getString("sPrefix","INV")?:"INV")}; var sAddr1 by remember{mutableStateOf(prefs.getString("sAddr1","")?:(prefs.getString("sAddr","")?:"").lineSequence().firstOrNull()?.trim()?:"")}; var sAddr2 by remember{mutableStateOf(prefs.getString("sAddr2","")?:(prefs.getString("sAddr","")?:"").lineSequence().drop(1).joinToString(" ").trim())}; var sPhone by remember{mutableStateOf(prefs.getString("sPhone","")?:"")}; var sEmail by remember{mutableStateOf(prefs.getString("sEmail","")?:"")}; var sLandmark by remember{mutableStateOf(prefs.getString("sLandmark","")?:"")}; var sPincode by remember{mutableStateOf(prefs.getString("sPincode","")?:"")}; var sGst by remember{mutableStateOf(prefs.getString("sGst","")?:"")}; var sBank by remember{mutableStateOf(prefs.getString("sBank","")?:"")}; var sIfsc by remember{mutableStateOf(prefs.getString("sIfsc","")?:"")}; var sJuris by remember{mutableStateOf(prefs.getString("sJuris","")?:"")}; var defTax by remember{mutableStateOf(prefs.getString("defTax","18")?:"18")}; var newPin by remember{mutableStateOf(currentPin)}; Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())){Text("SETTINGS",fontSize=24.sp,fontWeight=FontWeight.Bold); OutlinedTextField(newPin,{newPin=it},label={Text("Set Login PIN")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword),modifier=Modifier.fillMaxWidth()); OutlinedTextField(sName,{sName=it},label={Text("Firm Name")},modifier=Modifier.fillMaxWidth()); OutlinedTextField(sState,{sState=it},label={Text("My State Code (GST, e.g. 21)")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth()); OutlinedTextField(sPrefix,{sPrefix=it},label={Text("Invoice Prefix")},modifier=Modifier.fillMaxWidth()); OutlinedTextField(sAddr1,{sAddr1=it},label={Text("Address Line 1")},modifier=Modifier.fillMaxWidth()); OutlinedTextField(sAddr2,{sAddr2=it},label={Text("Address Line 2")},modifier=Modifier.fillMaxWidth()); OutlinedTextField(sPhone,{sPhone=it},label={Text("Phone")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Phone),modifier=Modifier.fillMaxWidth()); OutlinedTextField(sEmail,{sEmail=it},label={Text("Email")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email),modifier=Modifier.fillMaxWidth()); OutlinedTextField(sLandmark,{sLandmark=it},label={Text("Landmark")},modifier=Modifier.fillMaxWidth()); OutlinedTextField(sPincode,{sPincode=it},label={Text("Pincode")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth()); OutlinedTextField(sGst,{sGst=it},label={Text("GSTIN")},modifier=Modifier.fillMaxWidth()); OutlinedTextField(sBank,{sBank=it},label={Text("Bank Name & Acc")},modifier=Modifier.fillMaxWidth()); OutlinedTextField(sIfsc,{sIfsc=it},label={Text("IFSC")},modifier=Modifier.fillMaxWidth()); OutlinedTextField(sJuris,{sJuris=it},label={Text("Jurisdiction")},modifier=Modifier.fillMaxWidth()); OutlinedTextField(defTax,{defTax=it},label={Text("Default Tax %")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth()); Text("PenKhata "+APP_VERSION, fontSize=10.sp, color=Color.Gray, modifier=Modifier.padding(top=8.dp)); Button(onClick={prefs.edit().putString("sName",sName).putString("sState",sState).putString("sPrefix",sPrefix).putString("sAddr1",sAddr1).putString("sAddr2",sAddr2).putString("sPhone",sPhone).putString("sEmail",sEmail).putString("sLandmark",sLandmark).putString("sPincode",sPincode).putString("sGst",sGst).putString("sBank",sBank).putString("sIfsc",sIfsc).putString("sJuris",sJuris).putString("defTax",defTax).apply(); onPinSave(newPin); Toast.makeText(ctx,"Saved",Toast.LENGTH_SHORT).show()},modifier=Modifier.fillMaxWidth()){Text("SAVE")}; Card(Modifier.padding(vertical=10.dp)){Column(Modifier.padding(10.dp)){ Text("Backup & Restore", fontWeight=FontWeight.Bold); Text("Saves settings, invoices and ledger to a file you choose, so a reinstall does not mean re-typing everything. The app PIN is not included.", fontSize=10.sp); Spacer(Modifier.height(8.dp)); Button(onClick={ backupLauncher.launch("penkhata-backup-"+SimpleDateFormat("yyyyMMdd-HHmm", Locale.ENGLISH).format(Date())+".json") }, modifier=Modifier.fillMaxWidth()){ Text("BACKUP DATA") }; Spacer(Modifier.height(6.dp)); Button(onClick={ restoreLauncher.launch(arrayOf("application/json","text/plain","*/*")) }, modifier=Modifier.fillMaxWidth()){ Text("RESTORE DATA") } }}; Spacer(Modifier.height(60.dp))} }

// --- LOGIC ---
fun loadStock(ctx: Context): List<StockEntry> {
    val f = File(ctx.filesDir, "stock.json")
    if(!f.exists()) return emptyList()
    val out = mutableListOf<StockEntry>()
    try {
        val arr = JSONArray(f.readText())
        for(i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(StockEntry(o.optLong("id"), o.optString("model"), o.optString("imei"), o.optString("hsn"),
                o.optDouble("cost", 0.0), o.optDouble("mrp", 0.0), o.optInt("qty", 0), o.optString("supplier"), o.optString("addedOn")))
        }
    } catch(e: Exception) { }
    return out
}
fun saveStock(ctx: Context, list: List<StockEntry>) {
    val arr = JSONArray()
    for(e in list) {
        val o = JSONObject()
        o.put("id", e.id); o.put("model", e.model); o.put("imei", e.imei); o.put("hsn", e.hsn)
        o.put("cost", e.cost); o.put("mrp", e.mrp); o.put("qty", e.qty); o.put("supplier", e.supplier); o.put("addedOn", e.addedOn)
        arr.put(o)
    }
    writeFileAtomic(File(ctx.filesDir, "stock.json"), arr.toString())
}
// Purchase cost of a sold item: matched on IMEI first, then on model name. 0 when unknown.
fun stockCostFor(list: List<StockEntry>, item: InvItem): Double {
    list.firstOrNull { it.imei.isNotBlank() && it.imei == item.serial }?.let { return it.cost }
    list.firstOrNull { it.model.isNotBlank() && item.desc.lowercase().contains(it.model.lowercase()) }?.let { return it.cost }
    return 0.0
}
// Takes sold quantities out of stock. Unknown items are left alone rather than guessed at.
fun deductStock(ctx: Context, items: List<InvItem>) {
    val list = loadStock(ctx).toMutableList()
    var changed = false
    for(item in items) {
        val sold = item.qty.toInt().coerceAtLeast(0)
        if(sold == 0) continue
        var idx = list.indexOfFirst { it.imei.isNotBlank() && it.imei == item.serial }
        if(idx < 0) idx = list.indexOfFirst { it.model.isNotBlank() && item.desc.lowercase().contains(it.model.lowercase()) }
        if(idx >= 0) {
            val e = list[idx]
            list[idx] = e.copy(qty = (e.qty - sold).coerceAtLeast(0))
            changed = true
        }
    }
    if(changed) saveStock(ctx, list)
}
fun writeFileAtomic(f: File, text: String) {
    val tmp = File(f.parentFile, f.name + ".tmp")
    tmp.writeText(text)
    if(!tmp.renameTo(f)) { f.writeText(text); tmp.delete() }
}
// Everything needed to stand the app up on a fresh install: all settings, plus the
// invoice and ledger records so the GST tab is not empty after a restore.
// The PIN is deliberately left out of the file.
fun backupJson(ctx: Context): String {
    val prefs = ctx.getSharedPreferences("penkhata_data", Context.MODE_PRIVATE)
    val settings = JSONObject()
    for((k, v) in prefs.all) { if(k != "appPin" && v != null) settings.put(k, v) }
    val invoices = JSONArray(); val ledger = JSONArray()
    ctx.filesDir.listFiles()?.forEach { f ->
        try {
            when {
                f.name.startsWith("inv_") -> invoices.put(JSONObject(f.readText()))
                f.name.startsWith("led_") -> ledger.put(JSONObject(f.readText()))
            }
        } catch(e: Exception) { }
    }
    val root = JSONObject()
    root.put("type", "penkhata-backup")
    root.put("format", 1)
    root.put("savedAt", Date().toString())
    root.put("settings", settings)
    root.put("invoices", invoices)
    root.put("ledger", ledger)
    return root.toString(2)
}

fun restoreJson(ctx: Context, text: String): String {
    val root = try { JSONObject(text) } catch(e: Exception) { return "That file is not a PenKhata backup" }
    if(root.optString("type") != "penkhata-backup") return "That file is not a PenKhata backup"
    val prefs = ctx.getSharedPreferences("penkhata_data", Context.MODE_PRIVATE)
    val ed = prefs.edit()
    var settingsCount = 0
    val settings = root.optJSONObject("settings")
    if(settings != null) for(k in settings.keys()) {
        if(k == "appPin") continue
        val raw = settings.opt(k)
        // nextInv is stored as an Int and read back with getInt(), so keep the type
        if(raw is Number) ed.putInt(k, raw.toInt()) else ed.putString(k, raw?.toString() ?: "")
        settingsCount++
    }
    ed.apply()
    var inv = 0; var led = 0
    val stamp = System.currentTimeMillis()
    root.optJSONArray("invoices")?.let { arr ->
        for(i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            writeFileAtomic(File(ctx.filesDir, "inv_${stamp}_$i.json"), o.toString()); inv++
        }
    }
    root.optJSONArray("ledger")?.let { arr ->
        for(i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            writeFileAtomic(File(ctx.filesDir, "led_${stamp}_$i.json"), o.toString()); led++
        }
    }
    return "Restored $settingsCount settings, $inv invoices, $led ledger entries. Reopen the app to load them."
}

fun loadGstData(ctx: Context): Map<String,String> {
    var total=0.0; var tax=0.0; var skipped=0
    ctx.filesDir.listFiles()?.filter{it.name.startsWith("inv_")}?.forEach { f ->
        val j = try { JSONObject(f.readText()) } catch(e: Exception) { null }
        if(j == null) skipped++ else { total+=j.optDouble("total",0.0); tax+=j.optDouble("tax",0.0) }
    }
    return mapOf("total" to String.format("%.2f",total),"tax" to String.format("%.2f",tax),"skipped" to skipped.toString())
}
fun saveInvoiceData(ctx: Context, invNo: String, bName: String, bGst: String, calc: BillCalc, items: List<InvItem>) {
    val stock = loadStock(ctx)
    var cogs = 0.0
    for(i in items) cogs += stockCostFor(stock, i) * i.qty
    val j=JSONObject()
    j.put("d",Date().toString()); j.put("inv",invNo); j.put("buyer",bName); j.put("bgst",bGst)
    j.put("total",calc.grand); j.put("taxable",calc.taxable); j.put("tax",calc.totalTax)
    j.put("cgst",calc.cgst); j.put("sgst",calc.sgst); j.put("igst",calc.igst); j.put("interState",calc.interState)
    j.put("cogs",cogs); j.put("profit",calc.taxable-cogs)
    writeFileAtomic(File(ctx.filesDir,"inv_${System.currentTimeMillis()}.json"), j.toString())
}
fun saveLedgerEntry(ctx: Context, e: LedgerEntry) { val j=JSONObject(); j.put("date",e.date); j.put("party",e.party); j.put("type",e.type); j.put("amt",e.amount); j.put("desc",e.desc); writeFileAtomic(File(ctx.filesDir,"led_${System.currentTimeMillis()}.json"), j.toString()) }
fun loadLedger(ctx: Context): List<LedgerEntry> {
    val l=mutableListOf<LedgerEntry>()
    ctx.filesDir.listFiles()?.filter{it.name.startsWith("led_")}?.forEach { f ->
        val j = try { JSONObject(f.readText()) } catch(e: Exception) { null }
        if(j != null) l.add(LedgerEntry(0, j.optString("date"), j.optString("party"), j.optString("type"), j.optDouble("amt",0.0), j.optString("desc")))
    }
    return l
}
fun numberToWords(num: Long): String {
    if(num==0L) return "Zero"
    val u=arrayOf("","One","Two","Three","Four","Five","Six","Seven","Eight","Nine","Ten","Eleven","Twelve","Thirteen","Fourteen","Fifteen","Sixteen","Seventeen","Eighteen","Nineteen")
    val t=arrayOf("","","Twenty","Thirty","Forty","Fifty","Sixty","Seventy","Eighty","Ninety")
    fun rec(n:Long):String{
        if(n<20) return u[n.toInt()]
        if(n<100) return t[(n/10).toInt()] + " " + u[(n%10).toInt()]
        if(n<1000) return u[(n/100).toInt()] + " Hundred " + rec(n%100)
        if(n<100000) return rec(n/1000) + " Thousand " + rec(n%1000)
        if(n<10000000) return rec(n/100000) + " Lakh " + rec(n%100000)
        return rec(n/10000000) + " Crore " + rec(n%10000000)
    }
    return rec(num).trim().replace(Regex("\\s+"), " ")
}
fun amountInWords(amount: Double): String {
    var rupees = amount.toLong()
    var paise = Math.round((amount - rupees) * 100).toInt()
    if(paise >= 100) { rupees += 1; paise = 0 }
    val rw = if(rupees == 1L) "One Rupee" else "${numberToWords(rupees)} Rupees"
    return if(paise == 0) "$rw Only" else "$rw and ${numberToWords(paise.toLong())} Paise Only"
}
// Draws the amount in words inside the free width: shrinks the text until it fits,
// and only wraps to a second line if even the smallest size is too wide.
fun drawAmountInWords(c: Canvas, text: String, x: Float, baseY: Float, p: Paint, width: Float) {
    val startSize = p.textSize
    var size = startSize
    while(size > 7f && p.measureText(text) > width) { size -= 0.5f; p.textSize = size }
    if(p.measureText(text) <= width) {
        c.drawText(text, x, baseY, p)
    } else {
        var y = baseY
        for(l in wrapLines(text, p, width).take(2)) { c.drawText(l, x, y, p); y += p.textSize + 1f }
    }
    p.textSize = startSize
}
// Breaks text into lines that fit 'width': honours explicit newlines, wraps on spaces,
// and hard-splits a single word longer than the cell. Canvas.drawText never breaks on a
// newline character, so every multi-line value must go through this.
fun wrapLines(text: String, p: Paint, width: Float): List<String> {
    val out = mutableListOf<String>()
    if(text.isBlank()) return out
    for(raw in text.replace("\r\n", "\n").split("\n")) {
        val words = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if(words.isEmpty()) continue
        var line = ""
        for(w0 in words) {
            var word = w0
            while(p.measureText(word) > width && word.length > 1) {
                var cut = word.length
                while(cut > 1 && p.measureText(word.substring(0, cut)) > width) cut--
                if(line.isNotEmpty()) { out.add(line); line = "" }
                out.add(word.substring(0, cut)); word = word.substring(cut)
            }
            line = if(line.isEmpty()) word else "$line $word"
            if(p.measureText(line) > width && line.contains(' ')) {
                out.add(line.substringBeforeLast(' ')); line = line.substringAfterLast(' ')
            }
        }
        if(line.isNotEmpty()) out.add(line)
    }
    return out
}
fun qtyText(q: Double): String = if(q == q.toLong().toDouble()) q.toLong().toString() else q.toString()
fun blockHeight(text: String, p: Paint, width: Float, gap: Float = 2f): Float {
    val n = wrapLines(text, p, width).size
    return if(n == 0) 0f else n * (p.textSize + gap)
}
fun drawBlock(c: Canvas, text: String, x: Float, baseY: Float, p: Paint, width: Float, gap: Float = 2f): Float {
    var y = baseY
    for(l in wrapLines(text, p, width)) { c.drawText(l, x, y, p); y += p.textSize + gap }
    return y
}

fun createPdf(ctx: Context, isQuote: Boolean, invNo: String, date: String, payMode: String, delNote: String,
              sName: String, sAddr: String, sPhone: String, sEmail: String, sLandmark: String, sPincode: String,
              sGst: String, sBank: String, sIfsc: String, sJuris: String,
              bName: String, bAddr1: String, bAddr2: String, bLandmark: String, bPincode: String, bPhone: String,
              bGst: String, bState: String, items: List<InvItem>, calc: BillCalc, finance: String): File {
    val doc = PdfDocument(); val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
    val c = page.canvas; val p = Paint(); val bp = Paint().apply{style=Paint.Style.STROKE; strokeWidth=1f}
    val m=20f; val w=555f; val h=800f; val midX=m+w/2
    c.drawRect(m, m, m+w, m+h, bp)
    p.isFakeBoldText=true; p.textAlign=Paint.Align.CENTER; p.textSize=14f
    c.drawText(if(isQuote) "QUOTATION" else "TAX INVOICE", midX, m+15, p)
    c.drawLine(m, m+20, m+w, m+20, bp)
    // Seller (top) and buyer (bottom) cells: every value is wrapped inside its own cell and the
    // cell heights are measured from the wrapped text, so nothing crosses a divider or the page edge.
    val r1=m+20; val pad=8f
    val leftX=m+5; val leftW=midX-leftX-8f
    val qX=midX+(m+w-midX)/2; val labX=midX+4f; val labW=qX-labX-4f; val valX=qX+4f; val valW=(m+w)-valX-6f
    val nameP=Paint(p).apply{textSize=12f; isFakeBoldText=true; textAlign=Paint.Align.LEFT}
    val bodyP=Paint(p).apply{textSize=9f; isFakeBoldText=false; textAlign=Paint.Align.LEFT}
    val sDetail=listOf(
        if(sLandmark.isBlank()) "" else "Landmark: $sLandmark",
        if(sPincode.isBlank()) "" else "Pincode: $sPincode",
        if(sPhone.isBlank()) "" else "Ph: $sPhone",
        sEmail
    )
    val sGstLine=if(sGst.isBlank()) "" else "GSTIN: $sGst"
    val bGstLine=if(bGst.isBlank()) "" else "GSTIN: $bGst"
    val bStateLine=if(bState.isBlank()) "" else "State: $bState"
    val bDetail=listOf(bAddr1, bAddr2,
        if(bLandmark.isBlank()) "" else "Landmark: $bLandmark",
        if(bPincode.isBlank()) "" else "Pincode: $bPincode",
        if(bPhone.isBlank()) "" else "Ph: $bPhone")
    fun linesH(lines: List<String>): Float = lines.fold(0f) { acc, t -> acc + blockHeight(t, bodyP, leftW) }
    val sellerH=blockHeight(sName,nameP,leftW)+2f+blockHeight(sAddr,bodyP,leftW)+linesH(sDetail)+blockHeight(sGstLine,bodyP,leftW)+pad
    val buyerH=blockHeight("Buyer: $bName",nameP,leftW)+2f+linesH(bDetail)+blockHeight(bGstLine,bodyP,leftW)+blockHeight(bStateLine,bodyP,leftW)+pad
    val rows=listOf("No" to invNo, "Date" to date, "Del. Note" to delNote, "Pay Mode" to payMode)
    val rowHs=rows.map{ (l,v) -> maxOf(blockHeight(l,bodyP,labW), blockHeight(v,bodyP,valW))+4f }
    val topH=maxOf(sellerH, rowHs[0]+rowHs[1])+pad
    val botH=maxOf(buyerH, rowHs[2]+rowHs[3])+pad
    val midY=r1+topH; val r1End=midY+botH
    c.drawLine(midX,r1,midX,r1End,bp); c.drawLine(m,midY,m+w,midY,bp)
    var ty=r1+pad+nameP.textSize
    ty=drawBlock(c,sName,leftX,ty,nameP,leftW)+2f
    ty=drawBlock(c,sAddr,leftX,ty,bodyP,leftW)
    for(l in sDetail) ty=drawBlock(c,l,leftX,ty,bodyP,leftW)
    drawBlock(c,sGstLine,leftX,ty,bodyP,leftW)
    var buyY=midY+pad+nameP.textSize
    buyY=drawBlock(c,"Buyer: $bName",leftX,buyY,nameP,leftW)+2f
    for(l in bDetail) buyY=drawBlock(c,l,leftX,buyY,bodyP,leftW)
    buyY=drawBlock(c,bGstLine,leftX,buyY,bodyP,leftW)
    drawBlock(c,bStateLine,leftX,buyY,bodyP,leftW)
    val topSplit=r1+topH*(rowHs[0]/(rowHs[0]+rowHs[1])); val botSplit=midY+botH*(rowHs[2]/(rowHs[2]+rowHs[3]))
    c.drawLine(midX,topSplit,m+w,topSplit,bp); c.drawLine(midX,botSplit,m+w,botSplit,bp); c.drawLine(qX,r1,qX,r1End,bp)
    fun rightRow(l:String, v:String, top:Float) { val base=top+4f+bodyP.textSize; drawBlock(c,l,labX,base,bodyP,labW); drawBlock(c,v,valX,base,bodyP,valW) }
    rightRow(rows[0].first,rows[0].second,r1); rightRow(rows[1].first,rows[1].second,topSplit)
    rightRow(rows[2].first,rows[2].second,midY); rightRow(rows[3].first,rows[3].second,botSplit)
    p.textSize=10f; p.textAlign=Paint.Align.LEFT; p.isFakeBoldText=false

    val tTop=r1End; val hH=20f; c.drawLine(m,tTop,m+w,tTop,bp); c.drawLine(m,tTop+hH,m+w,tTop+hH,bp)
    val c1=m;val w1=30f;val c2=c1+w1;val w2=200f;val c3=c2+w2;val w3=50f;val c4=c3+w3;val w4=60f;val c5=c4+w4;val w5=70f;val c6=c5+w5;val w6=50f;val c7=c6+w6;val w7=95f
    fun vLine(top:Float,bot:Float){c.drawLine(c2,top,c2,bot,bp);c.drawLine(c3,top,c3,bot,bp);c.drawLine(c4,top,c4,bot,bp);c.drawLine(c5,top,c5,bot,bp);c.drawLine(c6,top,c6,bot,bp);c.drawLine(c7,top,c7,bot,bp)}
    vLine(tTop,tTop+hH); p.isFakeBoldText=true; p.textAlign=Paint.Align.CENTER
    c.drawText("SI",c1+w1/2,tTop+14,p); c.drawText("Desc",c2+w2/2,tTop+14,p); c.drawText("HSN",c3+w3/2,tTop+14,p); c.drawText("Qty",c4+w4/2,tTop+14,p); c.drawText("Rate Incl.",c5+w5/2,tTop+14,p); c.drawText("Per",c6+w6/2,tTop+14,p); c.drawText("Amt",c7+w7/2,tTop+14,p)
    p.isFakeBoldText=false; var y=tTop+hH
    val descW=w2-10f
    items.forEachIndexed{i,it->
        // The description wraps inside its own column and the row grows to fit, so it can
        // never run into HSN. Discount and serial go on the small line underneath.
        val descLines=wrapLines(it.desc, p, descW).take(3)
        val discNote=if(it.discount>0.004) "Disc ${fmt(it.discount)}%  -${fmt(it.qty*it.rate*it.discount/100.0)}" else ""
        val detailPaint=Paint(p).apply{ textSize=8f; textAlign=Paint.Align.LEFT }
        // serial and discount each get their own small line, so neither can straddle the column edge
        val detailLines=listOf(if(it.serial.isNotEmpty()) "SR/IMEI: ${it.serial}" else "", discNote)
            .filter{it.isNotBlank()}
            .flatMap{ wrapLines(it, detailPaint, descW).take(1) }
        val rh=Math.max(20f, 6f+descLines.size*12f+detailLines.size*10f)
        val taxRate=it.taxRate.coerceIn(0.0,100.0); val rowInc=it.qty*it.rate; val rowTax=rowInc*(it.discount.coerceIn(0.0,100.0)/100.0); val netInc=rowInc-rowTax; val taxF=1+(taxRate/100); val rowBase=netInc/taxF
        c.drawText("${i+1}",c1+w1/2,y+14,p); p.textAlign=Paint.Align.LEFT
        var lineY=y+14f
        for(l in descLines) { c.drawText(l,c2+5,lineY,p); lineY+=12f }
        for(l in detailLines) { c.drawText(l,c2+5,lineY+1f,detailPaint); lineY+=10f }
        p.textAlign=Paint.Align.CENTER; c.drawText(it.hsn,c3+w3/2,y+14,p); c.drawText(qtyText(it.qty),c4+w4/2,y+14,p)
        c.drawText(fmt(it.rate),c5+w5/2,y+14,p); c.drawText(it.unit,c6+w6/2,y+14,p)
        p.textAlign=Paint.Align.RIGHT; c.drawText(fmt(it.rate*it.qty),m+w-5,y+14,p); y+=rh
    }
    // Totals block grows upward from a fixed bottom line, so the extra rows a GST bill
    // needs (discount, IGST, round-off, payment) can never push into the declaration area.
    val tX=c7; val rowH=18f; val totalsBottom=700f
    val totalRows=mutableListOf<Pair<String,String>>()
    totalRows.add("Gross Value" to fmt(calc.gross))
    val totalDiscount=calc.itemDiscount+calc.billDiscount
    if(totalDiscount>0.004) totalRows.add("Less Discount" to "-"+fmt(totalDiscount))
    totalRows.add("Taxable Value" to fmt(calc.taxable))
    if(calc.interState) totalRows.add("IGST" to fmt(calc.igst))
    else { totalRows.add("CGST" to fmt(calc.cgst)); totalRows.add("SGST" to fmt(calc.sgst)) }
    if(Math.abs(calc.roundOff)>=0.005) totalRows.add("Round Off" to (if(calc.roundOff>0) "+" else "")+fmt(calc.roundOff))
    val totalsH=totalRows.size*rowH+26f
    val fTop=totalsBottom-totalsH
    vLine(tTop+hH,fTop); c.drawLine(m,fTop,m+w,fTop,bp)
    p.textAlign=Paint.Align.RIGHT; p.textSize=10f; p.isFakeBoldText=false
    var ry=fTop
    for((l,v) in totalRows) {
        c.drawText(l,tX-10,ry+13,p); c.drawText(v,m+w-5,ry+13,p)
        c.drawLine(tX,ry,tX,ry+rowH,bp); c.drawLine(tX,ry+rowH,m+w,ry+rowH,bp); ry+=rowH
    }
    p.isFakeBoldText=true
    c.drawText("Grand Total",tX-10,ry+17,p); c.drawText("\u20b9 "+fmt(calc.grand),m+w-5,ry+17,p)
    val dy=700f
    c.drawLine(tX,fTop,tX,dy,bp)
    p.isFakeBoldText=false; p.textAlign=Paint.Align.LEFT
    val leftLimit=tX-10-(m+5)-4f
    var ly=fTop+16f
    drawAmountInWords(c, "Amount: "+amountInWords(calc.grand), m+5, ly, p, leftLimit)
    p.textSize=9f
    if(calc.paid>0.004) {
        val parts=mutableListOf<String>()
        if(calc.cash>0.004) parts.add("Cash "+fmt(calc.cash))
        if(calc.upi>0.004) parts.add("UPI "+fmt(calc.upi))
        if(calc.card>0.004) parts.add("Card "+fmt(calc.card))
        ly+=14f
        drawAmountInWords(c, "Paid: \u20b9 "+fmt(calc.paid)+(if(parts.isEmpty()) "" else " ("+parts.joinToString(", ")+")"), m+5, ly, p, leftLimit)
    }
    if(Math.abs(calc.balance)>0.004) { ly+=13f; c.drawText("Balance Due: \u20b9 "+fmt(calc.balance),m+5,ly,p) }
    if(calc.exchange>0.004) { ly+=13f; c.drawText("Exchange adjusted: \u20b9 "+fmt(calc.exchange),m+5,ly,p) }
    if(finance.isNotBlank()) { ly+=13f; drawAmountInWords(c, "Finance: "+finance, m+5, ly, p, leftLimit) }
    p.textSize=10f
    if(sBank.isNotEmpty()) {
        val bankY=Math.min(ly+30f, dy-16f)
        if(bankY>ly+12f) {
            c.drawText("Bank Details:",m+5,bankY-9f,p); p.isFakeBoldText=true
            drawAmountInWords(c, "$sBank | $sIfsc", m+5, bankY, p, leftLimit); p.isFakeBoldText=false
        }
    }
    c.drawLine(m,dy,tX,dy,bp); p.isFakeBoldText=false; p.textSize=8f
    c.drawText("Declaration: We declare this invoice shows the actual price of goods.",m+5,dy+12,p)
    c.drawText("Subject to $sJuris Jurisdiction",m+5,dy+22,p); p.isFakeBoldText=true
    c.drawText("GOODS ONCE SOLD CANNOT BE RETURNED",m+5,dy+35,p)
    c.drawLine(c5,dy,m+w,dy,bp)
    p.textSize=10f; p.isFakeBoldText=true; p.textAlign=Paint.Align.CENTER
    val sigX = (c5 + m + w) / 2
    c.drawText("For, $sName",sigX,dy+20,p)
    c.drawText("Authorised Signatory",sigX,m+h-10,p)
    p.isFakeBoldText=false; p.textSize=8f
    c.drawText("Computer generated invoice.",midX,m+h+15,p)
    doc.finishPage(page); val n=if(isQuote)"Quote" else "Inv"; val f=File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),"${n}_${System.currentTimeMillis()}.pdf")
    doc.writeTo(FileOutputStream(f)); doc.close()
    return f
}
// Runs on the main thread; kept out of createPdf so drawing and writing can happen on IO.
fun openPdf(ctx: Context, f: File) {
    try {
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", f), "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "View"))
    } catch(e: Exception) {
        Toast.makeText(ctx, "Saved to Files, but no PDF viewer is installed", Toast.LENGTH_LONG).show()
    }
}
