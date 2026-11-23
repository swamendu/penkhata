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
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

data class InvItem(val desc: String, val serial: String, val hsn: String, val qty: Double, val rate: Double, val unit: String, val taxRate: Double)
val InvItemListSaver: Saver<List<InvItem>, String> = Saver(
    save = { list -> list.joinToString("\u0002") { "${it.desc}\u0001${it.serial}\u0001${it.hsn}\u0001${it.qty}\u0001${it.rate}\u0001${it.unit}\u0001${it.taxRate}" } },
    restore = { s ->
        if(s.isEmpty()) emptyList() else s.split("\u0002").mapNotNull { row ->
            val f = row.split("\u0001")
            if(f.size < 7) null else InvItem(f[0], f[1], f[2], f[3].toDoubleOrNull() ?: 0.0, f[4].toDoubleOrNull() ?: 0.0, f[5], f[6].toDoubleOrNull() ?: 18.0)
        }
    }
)
data class LedgerEntry(val id: Long, val date: String, val party: String, val type: String, val amount: Double, val desc: String)

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
            NavigationBarItem(selected = currentScreen=="reports", onClick = { currentScreen="reports" }, icon = { Icon(Icons.Default.Assessment, "GST") }, label = { Text("GST") })
            NavigationBarItem(selected = currentScreen=="history", onClick = { currentScreen="history" }, icon = { Icon(Icons.Default.History, "Files") }, label = { Text("Files") })
            NavigationBarItem(selected = currentScreen=="settings", onClick = { currentScreen="settings" }, icon = { Icon(Icons.Default.Settings, "Set") }, label = { Text("Set") })
        }}
    ) { p -> Box(Modifier.padding(p)) { when(currentScreen) {
        "dashboard" -> InvoiceScreen(false)
        "ledger" -> LedgerScreen()
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
    var invNo by rememberSaveable { mutableStateOf("1") }
    var date by rememberSaveable { mutableStateOf(SimpleDateFormat("dd-MMM-yyyy", Locale.ENGLISH).format(Date())) }
    var payMode by rememberSaveable { mutableStateOf("") }; var delNote by rememberSaveable { mutableStateOf("") }
    var bName by rememberSaveable { mutableStateOf("") }
    var bAddr1 by rememberSaveable { mutableStateOf("") }; var bAddr2 by rememberSaveable { mutableStateOf("") }
    var bLandmark by rememberSaveable { mutableStateOf("") }; var bPhone by rememberSaveable { mutableStateOf("") }
    var bGst by rememberSaveable { mutableStateOf("") }; var bState by rememberSaveable { mutableStateOf("") }
    var iDesc by rememberSaveable { mutableStateOf("") }; var iSerial by rememberSaveable { mutableStateOf("") }
    var iHsn by rememberSaveable { mutableStateOf("") }; var iQty by rememberSaveable { mutableStateOf("1") }
    var iRate by rememberSaveable { mutableStateOf("") }; var iUnit by rememberSaveable { mutableStateOf("pcs") }
    var iTax by rememberSaveable { mutableStateOf(prefs.getString("defTax", "18") ?: "18") }
    var items by rememberSaveable(stateSaver = InvItemListSaver) { mutableStateOf(listOf<InvItem>()) }

    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(if(isQuote) "QUOTATION" else "TAX INVOICE", fontSize = 24.sp, fontWeight = FontWeight.Bold, color=Color.Blue)
            TextButton(onClick = { invNo="1"; bName=""; bAddr1=""; bAddr2=""; bLandmark=""; bPhone=""; bGst=""; bState=""; items=emptyList(); Toast.makeText(ctx, "Cleared", Toast.LENGTH_SHORT).show() }) { Text("RESET", color = Color.Red) }
        }
        Card(Modifier.padding(vertical=5.dp)) { Column(Modifier.padding(10.dp)) {
            Row { OutlinedTextField(invNo, {invNo=it}, label={Text("Inv No")}, modifier=Modifier.weight(1f)); Spacer(Modifier.width(5.dp)); OutlinedTextField(date, {date=it}, label={Text("Date")}, modifier=Modifier.weight(1f)) }
            OutlinedTextField(payMode, {payMode=it}, label={Text("Pay Mode")}, modifier=Modifier.fillMaxWidth())
            OutlinedTextField(delNote, {delNote=it}, label={Text("Delivery Note")}, modifier=Modifier.fillMaxWidth())
        }}
        Card(Modifier.padding(vertical=5.dp)) { Column(Modifier.padding(10.dp)) {
            Text("Buyer Details", fontWeight=FontWeight.Bold)
            OutlinedTextField(bName, {bName=it}, label={Text("Name")}, modifier=Modifier.fillMaxWidth())
            OutlinedTextField(bAddr1, {bAddr1=it}, label={Text("Address Line 1")}, modifier=Modifier.fillMaxWidth())
            OutlinedTextField(bAddr2, {bAddr2=it}, label={Text("Address Line 2")}, modifier=Modifier.fillMaxWidth())
            OutlinedTextField(bLandmark, {bLandmark=it}, label={Text("Landmark")}, modifier=Modifier.fillMaxWidth())
            OutlinedTextField(bPhone, {bPhone=it}, label={Text("Phone")}, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Phone), modifier=Modifier.fillMaxWidth())
            Row { OutlinedTextField(bGst, {bGst=it}, label={Text("GSTIN")}, modifier=Modifier.weight(1f)); Spacer(Modifier.width(5.dp)); OutlinedTextField(bState, {bState=it}, label={Text("State Code")}, modifier=Modifier.weight(1f)) }
        }}
        Card(Modifier.padding(vertical=5.dp)) { Column(Modifier.padding(10.dp)) {
            Text("Add Item", fontWeight=FontWeight.Bold)
            OutlinedTextField(iDesc, {iDesc=it}, label={Text("Item Name")}, modifier=Modifier.fillMaxWidth())
            OutlinedTextField(iSerial, {iSerial=it}, label={Text("Serial / IMEI")}, modifier=Modifier.fillMaxWidth())
            Row { OutlinedTextField(iHsn, {iHsn=it}, label={Text("HSN")}, modifier=Modifier.weight(1f)); Spacer(Modifier.width(5.dp)); OutlinedTextField(iUnit, {iUnit=it}, label={Text("Unit")}, modifier=Modifier.weight(1f)) }
            Row { OutlinedTextField(iQty, {iQty=it}, label={Text("Qty")}, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number), modifier=Modifier.weight(1f)); Spacer(Modifier.width(5.dp)); OutlinedTextField(iRate, {iRate=it}, label={Text("Rate (Inc. Tax)")}, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number), modifier=Modifier.weight(1f)) }
            Row(Modifier.padding(top=5.dp)) { OutlinedTextField(iTax, {iTax=it}, label={Text("GST %")}, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number), modifier=Modifier.weight(1f)); Spacer(Modifier.width(5.dp)); Button(onClick={ if(iDesc.isNotEmpty()){ items=items+InvItem(iDesc,iSerial,iHsn,iQty.toDoubleOrNull()?:1.0,iRate.toDoubleOrNull()?:0.0,iUnit,iTax.toDoubleOrNull()?:18.0); iDesc=""; iSerial=""; iQty="1"; iRate="" } }, modifier=Modifier.weight(1f).height(55.dp)) { Text("ADD") } }
        }}
        items.forEachIndexed{ idx, it -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("${idx+1}. ${it.desc} (${it.qty} ${it.unit})", Modifier.weight(1f)); IconButton(onClick={ val m=items.toMutableList(); m.removeAt(idx); items=m }) { Icon(Icons.Default.Delete,"Del",tint=Color.Red) } }; Divider() }
        Spacer(Modifier.height(20.dp))
        Button(onClick={
            val sName = prefs.getString("sName", "") ?: ""
            if(sName.isEmpty()) Toast.makeText(ctx, "Setup Settings First!", Toast.LENGTH_SHORT).show()
            else {
                if(!isQuote) saveInvoiceData(ctx, invNo, bName, bGst, items)
                createPdf(ctx, isQuote, invNo, date, payMode, delNote, sName, listOf(prefs.getString("sAddr1","")?:"", prefs.getString("sAddr2","")?:"").filter{it.isNotBlank()}.joinToString("\n"), prefs.getString("sPhone","")?:"", prefs.getString("sEmail","")?:"", prefs.getString("sLandmark","")?:"", prefs.getString("sGst","")?:"", prefs.getString("sBank","")?:"", prefs.getString("sIfsc","")?:"", prefs.getString("sJuris","")?:"", bName, bAddr1, bAddr2, bLandmark, bPhone, bGst, bState, items)
            }
        }, modifier=Modifier.fillMaxWidth(), colors=ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))) { Text("GENERATE PDF") }
        Spacer(Modifier.height(60.dp))
    }
}

// --- SECONDARY SCREENS ---
@Composable fun LedgerScreen() { val ctx=LocalContext.current; var p by remember{mutableStateOf("")}; var t by remember{mutableStateOf("DEBIT")}; var a by remember{mutableStateOf("")}; var d by remember{mutableStateOf("")}; var e by remember{mutableStateOf(loadLedger(ctx))}; Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())){ Text("LEDGER",fontSize=24.sp,fontWeight=FontWeight.Bold); Card(Modifier.padding(vertical=10.dp)){Column(Modifier.padding(10.dp)){ OutlinedTextField(p,{p=it},label={Text("Party")},modifier=Modifier.fillMaxWidth()); Row{Button(onClick={t="DEBIT"},colors=ButtonDefaults.buttonColors(containerColor=if(t=="DEBIT")Color.Red else Color.Gray),modifier=Modifier.weight(1f)){Text("DEBIT")}; Spacer(Modifier.width(5.dp)); Button(onClick={t="CREDIT"},colors=ButtonDefaults.buttonColors(containerColor=if(t=="CREDIT")Color.Green else Color.Gray),modifier=Modifier.weight(1f)){Text("CREDIT")}}; OutlinedTextField(a,{a=it},label={Text("Amount")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth()); OutlinedTextField(d,{d=it},label={Text("Note")},modifier=Modifier.fillMaxWidth()); Button(onClick={if(p.isNotEmpty()&&a.isNotEmpty()){val en=LedgerEntry(System.currentTimeMillis(),SimpleDateFormat("dd-MM").format(Date()),p,t,a.toDouble(),d); saveLedgerEntry(ctx,en); e=loadLedger(ctx); p="";a=""}},modifier=Modifier.fillMaxWidth()){Text("ADD")}}}; e.reversed().forEach{Row(Modifier.padding(8.dp).fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Column{Text(it.party,fontWeight=FontWeight.Bold);Text(it.desc,fontSize=10.sp)}; Column(horizontalAlignment=Alignment.End){Text(it.type,color=if(it.type=="DEBIT")Color.Red else Color.Green,fontSize=10.sp);Text("₹${it.amount}",fontWeight=FontWeight.Bold)}};Divider()}; Spacer(Modifier.height(50.dp))}}
@Composable fun GSTReportScreen() {
    val ctx = LocalContext.current
    val d = remember { loadGstData(ctx) }
    Column(Modifier.padding(16.dp)) {
        Text("GST DASHBOARD", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Card(Modifier.padding(vertical = 10.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("Summary", fontWeight = FontWeight.Bold)
                Divider(Modifier.padding(vertical = 5.dp))
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("Sales:"); Text("₹${d["total"]}") }
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("Tax:"); Text("₹${d["tax"]}") }
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
@Composable fun SettingsScreen(currentPin: String, onPinSave: (String)->Unit) { val ctx=LocalContext.current; val prefs=remember{ctx.getSharedPreferences("penkhata_data",Context.MODE_PRIVATE)}; var sName by remember{mutableStateOf(prefs.getString("sName","")?:"")}; var sAddr1 by remember{mutableStateOf(prefs.getString("sAddr1","")?:(prefs.getString("sAddr","")?:"").lineSequence().firstOrNull()?.trim()?:"")}; var sAddr2 by remember{mutableStateOf(prefs.getString("sAddr2","")?:(prefs.getString("sAddr","")?:"").lineSequence().drop(1).joinToString(" ").trim())}; var sPhone by remember{mutableStateOf(prefs.getString("sPhone","")?:"")}; var sEmail by remember{mutableStateOf(prefs.getString("sEmail","")?:"")}; var sLandmark by remember{mutableStateOf(prefs.getString("sLandmark","")?:"")}; var sGst by remember{mutableStateOf(prefs.getString("sGst","")?:"")}; var sBank by remember{mutableStateOf(prefs.getString("sBank","")?:"")}; var sIfsc by remember{mutableStateOf(prefs.getString("sIfsc","")?:"")}; var sJuris by remember{mutableStateOf(prefs.getString("sJuris","")?:"")}; var defTax by remember{mutableStateOf(prefs.getString("defTax","18")?:"18")}; var newPin by remember{mutableStateOf(currentPin)}; Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())){Text("SETTINGS",fontSize=24.sp,fontWeight=FontWeight.Bold); OutlinedTextField(newPin,{newPin=it},label={Text("Set Login PIN")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword),modifier=Modifier.fillMaxWidth()); OutlinedTextField(sName,{sName=it},label={Text("Firm Name")},modifier=Modifier.fillMaxWidth()); OutlinedTextField(sAddr1,{sAddr1=it},label={Text("Address Line 1")},modifier=Modifier.fillMaxWidth()); OutlinedTextField(sAddr2,{sAddr2=it},label={Text("Address Line 2")},modifier=Modifier.fillMaxWidth()); OutlinedTextField(sPhone,{sPhone=it},label={Text("Phone")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Phone),modifier=Modifier.fillMaxWidth()); OutlinedTextField(sEmail,{sEmail=it},label={Text("Email")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email),modifier=Modifier.fillMaxWidth()); OutlinedTextField(sLandmark,{sLandmark=it},label={Text("Landmark")},modifier=Modifier.fillMaxWidth()); OutlinedTextField(sGst,{sGst=it},label={Text("GSTIN")},modifier=Modifier.fillMaxWidth()); OutlinedTextField(sBank,{sBank=it},label={Text("Bank Name & Acc")},modifier=Modifier.fillMaxWidth()); OutlinedTextField(sIfsc,{sIfsc=it},label={Text("IFSC")},modifier=Modifier.fillMaxWidth()); OutlinedTextField(sJuris,{sJuris=it},label={Text("Jurisdiction")},modifier=Modifier.fillMaxWidth()); OutlinedTextField(defTax,{defTax=it},label={Text("Default Tax %")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth()); Button(onClick={prefs.edit().putString("sName",sName).putString("sAddr1",sAddr1).putString("sAddr2",sAddr2).putString("sPhone",sPhone).putString("sEmail",sEmail).putString("sLandmark",sLandmark).putString("sGst",sGst).putString("sBank",sBank).putString("sIfsc",sIfsc).putString("sJuris",sJuris).putString("defTax",defTax).apply(); onPinSave(newPin); Toast.makeText(ctx,"Saved",Toast.LENGTH_SHORT).show()},modifier=Modifier.fillMaxWidth()){Text("SAVE")}; Spacer(Modifier.height(60.dp))} }

// --- LOGIC ---
fun writeFileAtomic(f: File, text: String) {
    val tmp = File(f.parentFile, f.name + ".tmp")
    tmp.writeText(text)
    if(!tmp.renameTo(f)) { f.writeText(text); tmp.delete() }
}
fun loadGstData(ctx: Context): Map<String,String> {
    var total=0.0; var tax=0.0; var skipped=0
    ctx.filesDir.listFiles()?.filter{it.name.startsWith("inv_")}?.forEach { f ->
        val j = try { JSONObject(f.readText()) } catch(e: Exception) { null }
        if(j == null) skipped++ else { total+=j.optDouble("total",0.0); tax+=j.optDouble("tax",0.0) }
    }
    return mapOf("total" to String.format("%.2f",total),"tax" to String.format("%.2f",tax),"skipped" to skipped.toString())
}
fun saveInvoiceData(ctx: Context, invNo: String, bName: String, bGst: String, items: List<InvItem>) { val j=JSONObject(); j.put("d",Date().toString()); j.put("inv",invNo); var t=0.0; var x=0.0; items.forEach{val v=it.qty*it.rate; val tr=it.taxRate.coerceIn(0.0,100.0); t+=v; x+=v-(v/(1+tr/100))}; j.put("total",t); j.put("tax",x); writeFileAtomic(File(ctx.filesDir,"inv_${System.currentTimeMillis()}.json"), j.toString()) }
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
              sName: String, sAddr: String, sPhone: String, sEmail: String, sLandmark: String,
              sGst: String, sBank: String, sIfsc: String, sJuris: String,
              bName: String, bAddr1: String, bAddr2: String, bLandmark: String, bPhone: String,
              bGst: String, bState: String, items: List<InvItem>) {
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
    val contact=listOf(sPhone,sEmail,sLandmark).filter{it.isNotBlank()}.joinToString("  |  ")
    val sGstLine=if(sGst.isBlank()) "" else "GSTIN: $sGst"
    val bGstLine=if(bGst.isBlank()) "" else "GSTIN: $bGst"
    val bStateLine=if(bState.isBlank()) "" else "State: $bState"
    val bAddr=listOf(bAddr1, bAddr2, if(bLandmark.isBlank()) "" else "Landmark: $bLandmark", if(bPhone.isBlank()) "" else "Ph: $bPhone").filter{it.isNotBlank()}.joinToString("\n")
    val sellerH=blockHeight(sName,nameP,leftW)+2f+blockHeight(sAddr,bodyP,leftW)+blockHeight(contact,bodyP,leftW)+blockHeight(sGstLine,bodyP,leftW)+pad
    val buyerH=blockHeight("Buyer: $bName",nameP,leftW)+2f+blockHeight(bAddr,bodyP,leftW)+blockHeight(bGstLine,bodyP,leftW)+blockHeight(bStateLine,bodyP,leftW)+pad
    val rows=listOf("No" to invNo, "Date" to date, "Note" to delNote, "Terms" to payMode)
    val rowHs=rows.map{ (l,v) -> maxOf(blockHeight(l,bodyP,labW), blockHeight(v,bodyP,valW))+4f }
    val topH=maxOf(sellerH, rowHs[0]+rowHs[1])+pad
    val botH=maxOf(buyerH, rowHs[2]+rowHs[3])+pad
    val midY=r1+topH; val r1End=midY+botH
    c.drawLine(midX,r1,midX,r1End,bp); c.drawLine(m,midY,m+w,midY,bp)
    var ty=r1+pad+nameP.textSize
    ty=drawBlock(c,sName,leftX,ty,nameP,leftW)+2f
    ty=drawBlock(c,sAddr,leftX,ty,bodyP,leftW)
    ty=drawBlock(c,contact,leftX,ty,bodyP,leftW)
    drawBlock(c,sGstLine,leftX,ty,bodyP,leftW)
    var buyY=midY+pad+nameP.textSize
    buyY=drawBlock(c,"Buyer: $bName",leftX,buyY,nameP,leftW)+2f
    buyY=drawBlock(c,bAddr,leftX,buyY,bodyP,leftW)
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
    c.drawText("SI",c1+w1/2,tTop+14,p); c.drawText("Desc",c2+w2/2,tTop+14,p); c.drawText("HSN",c3+w3/2,tTop+14,p); c.drawText("Qty",c4+w4/2,tTop+14,p); c.drawText("Rate",c5+w5/2,tTop+14,p); c.drawText("Per",c6+w6/2,tTop+14,p); c.drawText("Amt",c7+w7/2,tTop+14,p)
    p.isFakeBoldText=false; var y=tTop+hH; var gTotal=0.0; var totalTaxable=0.0; var totalTax=0.0
    items.forEachIndexed{i,it->
        val rh=if(it.serial.isNotEmpty()) 30f else 20f
        val taxRate=it.taxRate.coerceIn(0.0,100.0); val rowInc=it.qty*it.rate; val taxF=1+(taxRate/100); val rowBase=rowInc/taxF; val rowTax=rowInc-rowBase
        gTotal+=rowInc; totalTaxable+=rowBase; totalTax+=rowTax
        c.drawText("${i+1}",c1+w1/2,y+14,p); p.textAlign=Paint.Align.LEFT
        c.drawText(it.desc,c2+5,y+14,p); if(it.serial.isNotEmpty()){val ps=Paint(p);ps.textSize=8f;c.drawText("SR/IMEI: ${it.serial}",c2+5,y+26,ps)}
        p.textAlign=Paint.Align.CENTER; c.drawText(it.hsn,c3+w3/2,y+14,p); c.drawText(qtyText(it.qty),c4+w4/2,y+14,p)
        c.drawText(String.format("%.2f",rowBase),c5+w5/2,y+14,p); c.drawText(it.unit,c6+w6/2,y+14,p)
        p.textAlign=Paint.Align.RIGHT; c.drawText(String.format("%.2f",rowBase*it.qty),m+w-5,y+14,p); y+=rh
    }
    val fTop=m+h-200f; vLine(tTop+hH,fTop); c.drawLine(m,fTop,m+w,fTop,bp); y=fTop; val tX=c7; p.textAlign=Paint.Align.RIGHT
    fun row(l:String,v:String){c.drawText(l,tX-10,y+14,p);c.drawText(v,m+w-5,y+14,p);c.drawLine(tX,y,tX,y+20,bp);c.drawLine(tX,y+20,m+w,y+20,bp);y+=20f}
    row("Total Value",String.format("%.2f",totalTaxable)); row("SGST",String.format("%.2f",totalTax/2)); row("CGST",String.format("%.2f",totalTax/2))
    p.isFakeBoldText=true; c.drawText("Grand Total",tX-10,y+14,p); c.drawText("₹ ${String.format("%.2f",gTotal)}",m+w-5,y+14,p);

    // CALCULATE SIGNATURE BOX TOP
    val fY=fTop+20; val by=fY+40; val dy=by+40; val sigY=dy+20 // Approximate height

    // *** FIXED LINE STOP ***
    // The line now stops at 'y' (which is at the bottom of Grand Total row)
    c.drawLine(tX, fTop, tX, y, bp)

    // Footer Text (Words, Bank, Decl)
    p.textAlign=Paint.Align.LEFT; p.isFakeBoldText=false
    drawAmountInWords(c, "Amount: " + amountInWords(gTotal), m+5, fY, p, tX-10-(m+5)-4f)
    if(sBank.isNotEmpty()){ c.drawLine(m,by,tX,by,bp); c.drawText("Bank Details:",m+5,by-25,p); p.isFakeBoldText=true; c.drawText("$sBank | $sIfsc",m+5,by-10,p) }
    c.drawLine(m,dy,tX,dy,bp); p.isFakeBoldText=false; p.textSize=8f
    c.drawText("Declaration: We declare this invoice shows the actual price of goods.",m+5,dy+12,p)
    c.drawText("Subject to $sJuris Jurisdiction",m+5,dy+22,p); p.isFakeBoldText=true
    c.drawText("GOODS ONCE SOLD CANNOT BE RETURNED",m+5,dy+35,p)

    // SIGNATURE BOX (FULL WIDTH BOTTOM RIGHT)
    c.drawLine(c5,dy,m+w,dy,bp) // Top line of box
    p.textSize=10f; p.isFakeBoldText=true; p.textAlign=Paint.Align.CENTER
    val sigX = (c5 + m + w) / 2
    c.drawText("For, $sName",sigX,dy+20,p)
    c.drawText("Authorised Signatory",sigX,m+h-10,p)

    p.isFakeBoldText=false; p.textSize=8f
    c.drawText("Computer generated invoice.",midX,m+h+15,p)
    doc.finishPage(page); val n=if(isQuote)"Quote" else "Inv"; val f=File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),"${n}_${System.currentTimeMillis()}.pdf")
    doc.writeTo(FileOutputStream(f)); doc.close()
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).apply{setDataAndType(FileProvider.getUriForFile(ctx,"${ctx.packageName}.provider",f),"application/pdf");addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"View"))
}
