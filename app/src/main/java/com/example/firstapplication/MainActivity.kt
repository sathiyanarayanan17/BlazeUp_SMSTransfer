package com.example.smsreader

import android.Manifest
import android.app.DatePickerDialog
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Telephony
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SMSReaderApp()
                }
            }
        }
    }
}

data class SMSMessage(
    val sender: String,
    val body: String,
    val date: Long,
    val formattedDate: String
)

data class SenderFilter(
    val name: String,
    var isEnabled: Boolean = true
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SMSReaderApp() {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(checkSMSPermission(context)) }
    var messages by remember { mutableStateOf<List<SMSMessage>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var showSnackbar by remember { mutableStateOf(false) }
    var snackbarMessage by remember { mutableStateOf("") }
    var showFilterDialog by remember { mutableStateOf(false) }
    var showAddProviderDialog by remember { mutableStateOf(false) }
    var newProviderName by remember { mutableStateOf("") }
    val csvPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val csvText = readCsvFromUri(context, uri)
            snackbarMessage = "CSV uploaded successfully"
            showSnackbar = true
        } else {
            snackbarMessage = "No file selected"
            showSnackbar = true
        }
    }


    // Date range state - default to last 30 days
    val calendar = Calendar.getInstance()
    val endDate = calendar.timeInMillis
    calendar.add(Calendar.DAY_OF_YEAR, -30)
    val startDate = calendar.timeInMillis

    var selectedStartDate by remember { mutableStateOf(startDate) }
    var selectedEndDate by remember { mutableStateOf(endDate) }

    // Sender filters - all enabled by default
    val defaultSenders = listOf(
        "FEDSCP", "HDFC", "ICICI", "AXIS", "SBI", "INDUS", "KOTAK", "CANBNK"
    )
    var senderFilters by remember {
        mutableStateOf(defaultSenders.map { SenderFilter(it, true) })
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasPermission = isGranted
        if (isGranted) {
            snackbarMessage = "Permission granted!"
            showSnackbar = true
        } else {
            snackbarMessage = "Permission denied. Cannot read SMS."
            showSnackbar = true
        }
    }

    val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bank SMS Reader") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        snackbarHost = {
            if (showSnackbar) {
                Snackbar(
                    modifier = Modifier.padding(16.dp),
                    action = {
                        TextButton(onClick = { showSnackbar = false }) {
                            Text("Dismiss")
                        }
                    }
                ) {
                    Text(snackbarMessage)
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            if (!hasPermission) {
                PermissionRequestScreen(
                    onRequestPermission = {
                        permissionLauncher.launch(Manifest.permission.READ_SMS)
                    }
                )
            } else {
                // Date Range Selection
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Text(
                            text = "Date Range",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            OutlinedButton(
                                onClick = {
                                    showDatePicker(
                                        context = context,
                                        initialDate = selectedStartDate,
                                        onDateSelected = { selectedStartDate = it }
                                    )
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Start Date", style = MaterialTheme.typography.labelSmall)
                                    Text(dateFormat.format(Date(selectedStartDate)))
                                }
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            OutlinedButton(
                                onClick = {
                                    showDatePicker(
                                        context = context,
                                        initialDate = selectedEndDate,
                                        onDateSelected = { selectedEndDate = it }
                                    )
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("End Date", style = MaterialTheme.typography.labelSmall)
                                    Text(dateFormat.format(Date(selectedEndDate)))
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Sender Filter Button
                OutlinedButton(
                    onClick = { showFilterDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val enabledCount = senderFilters.count { it.isEnabled }
                    Text("Message Senders ($enabledCount/${senderFilters.size} selected)")
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        isLoading = true
                        val enabledSenders = senderFilters.filter { it.isEnabled }.map { it.name }
                        messages = readBankSMS(
                            context = context,
                            startDate = selectedStartDate,
                            endDate = selectedEndDate,
                            senders = enabledSenders
                        )
                        isLoading = false
                        snackbarMessage = "Found ${messages.size} messages"
                        showSnackbar = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = senderFilters.any { it.isEnabled }
                ) {
                    Text("Load Messages")
                }
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = {
                        csvPickerLauncher.launch(
                            arrayOf("text/csv", "text/comma-separated-values")
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Upload CSV")
                }


                Spacer(modifier = Modifier.height(8.dp))

                if (messages.isNotEmpty()) {
                    Button(
                        onClick = {
                            exportToCSV(context, messages)
                            snackbarMessage = "CSV exported successfully!"
                            showSnackbar = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Export to CSV")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    Text(
                        text = "Messages (${messages.size})",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(messages) { message ->
                            MessageCard(message)
                        }
                    }
                }
            }
        }
    }

    // Sender Filter Dialog
    if (showFilterDialog) {
        AlertDialog(
            onDismissRequest = { showFilterDialog = false },
            title = { Text("Select Message Senders") },
            text = {
                LazyColumn {
                    items(senderFilters.size) { index ->
                        val filter = senderFilters[index]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Checkbox(
                                    checked = filter.isEnabled,
                                    onCheckedChange = { isChecked ->
                                        senderFilters = senderFilters.toMutableList().apply {
                                            this[index] = filter.copy(isEnabled = isChecked)
                                        }
                                    }
                                )
                                Text(
                                    text = filter.name,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                            // Only show delete button for custom providers (not default ones)
                            if (!defaultSenders.contains(filter.name)) {
                                IconButton(
                                    onClick = {
                                        senderFilters = senderFilters.toMutableList().apply {
                                            removeAt(index)
                                        }
                                    }
                                ) {
                                    Text("×", style = MaterialTheme.typography.titleLarge)
                                }
                            }
                        }
                    }
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { showAddProviderDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("+ Add Custom Provider")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFilterDialog = false }) {
                    Text("Done")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        senderFilters = senderFilters.map { it.copy(isEnabled = true) }
                    }
                ) {
                    Text("Select All")
                }
            }
        )
    }

    // Add Provider Dialog
    if (showAddProviderDialog) {
        AlertDialog(
            onDismissRequest = {
                showAddProviderDialog = false
                newProviderName = ""
            },
            title = { Text("Add Custom Provider") },
            text = {
                Column {
                    Text(
                        text = "Enter the sender name or keyword to filter:",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    OutlinedTextField(
                        value = newProviderName,
                        onValueChange = { newProviderName = it.uppercase() },
                        label = { Text("Provider Name") },
                        placeholder = { Text("e.g., PAYTM") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newProviderName.isNotBlank() &&
                            !senderFilters.any { it.name.equals(newProviderName, ignoreCase = true) }) {
                            senderFilters = senderFilters + SenderFilter(newProviderName.trim(), true)
                            snackbarMessage = "Added provider: $newProviderName"
                            showSnackbar = true
                        } else if (senderFilters.any { it.name.equals(newProviderName, ignoreCase = true) }) {
                            snackbarMessage = "Provider already exists"
                            showSnackbar = true
                        }
                        showAddProviderDialog = false
                        newProviderName = ""
                    },
                    enabled = newProviderName.isNotBlank()
                ) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showAddProviderDialog = false
                        newProviderName = ""
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

fun showDatePicker(
    context: Context,
    initialDate: Long,
    onDateSelected: (Long) -> Unit
) {
    val calendar = Calendar.getInstance().apply {
        timeInMillis = initialDate
    }

    DatePickerDialog(
        context,
        { _, year, month, dayOfMonth ->
            val selectedCalendar = Calendar.getInstance().apply {
                set(year, month, dayOfMonth, 0, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }
            onDateSelected(selectedCalendar.timeInMillis)
        },
        calendar.get(Calendar.YEAR),
        calendar.get(Calendar.MONTH),
        calendar.get(Calendar.DAY_OF_MONTH)
    ).show()
}

@Composable
fun PermissionRequestScreen(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "SMS Permission Required",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        Text(
            text = "This app needs permission to read SMS messages from your bank.",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(bottom = 24.dp)
        )
        Button(onClick = onRequestPermission) {
            Text("Grant Permission")
        }
    }
}

@Composable
fun MessageCard(message: SMSMessage) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = message.sender,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = message.formattedDate,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = message.body,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

fun checkSMSPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.READ_SMS
    ) == PackageManager.PERMISSION_GRANTED
}

fun readBankSMS(
    context: Context,
    startDate: Long,
    endDate: Long,
    senders: List<String>
): List<SMSMessage> {
    val messages = mutableListOf<SMSMessage>()
    val contentResolver: ContentResolver = context.contentResolver

    val uri: Uri = Telephony.Sms.Inbox.CONTENT_URI
    val projection = arrayOf(
        Telephony.Sms.ADDRESS,
        Telephony.Sms.BODY,
        Telephony.Sms.DATE
    )

    // Query messages within the date range
    val selection = "${Telephony.Sms.DATE} >= ? AND ${Telephony.Sms.DATE} <= ?"
    val selectionArgs = arrayOf(startDate.toString(), endDate.toString())
    val sortOrder = "${Telephony.Sms.DATE} DESC"

    val cursor = contentResolver.query(
        uri,
        projection,
        selection,
        selectionArgs,
        sortOrder
    )

    cursor?.use {
        val addressIndex = it.getColumnIndex(Telephony.Sms.ADDRESS)
        val bodyIndex = it.getColumnIndex(Telephony.Sms.BODY)
        val dateIndex = it.getColumnIndex(Telephony.Sms.DATE)

        val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())

        while (it.moveToNext()) {
            val address = it.getString(addressIndex) ?: ""
            val body = it.getString(bodyIndex) ?: ""
            val date = it.getLong(dateIndex)

            // Check if sender contains any of the selected bank names
            if (senders.any { sender -> address.contains(sender, ignoreCase = true) }) {
                messages.add(
                    SMSMessage(
                        sender = address,
                        body = body,
                        date = date,
                        formattedDate = dateFormat.format(Date(date))
                    )
                )
            }
        }
    }

    return messages
}

fun exportToCSV(context: Context, messages: List<SMSMessage>) {
    val fileName = "bank_sms_${System.currentTimeMillis()}.csv"
    val file = File(context.getExternalFilesDir(null), fileName)

    file.bufferedWriter().use { writer ->
        // Write CSV header
        writer.write("Sender,Date,Message\n")

        // Write each message
        messages.forEach { message ->
            val csvLine = buildString {
                append("\"${message.sender.replace("\"", "\"\"")}\",")
                append("\"${message.formattedDate}\",")
                append("\"${message.body.replace("\"", "\"\"")}\"")
                append("\n")
            }
            writer.write(csvLine)
        }
    }

    // Share the CSV file
    shareCsvFile(context, file)
}

fun shareCsvFile(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )

    val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    context.startActivity(
        android.content.Intent.createChooser(shareIntent, "Share CSV file")
    )
}
fun readCsvFromUri(context: Context, uri: Uri): String {
    return context.contentResolver
        .openInputStream(uri)
        ?.bufferedReader()
        ?.use { it.readText() }
        ?: ""
}
