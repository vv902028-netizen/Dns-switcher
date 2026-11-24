package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.AddressValidator
import com.example.data.DnsServerEntity
import com.example.data.TransportMode
import com.example.ui.theme.LocalPulsStatusColors
import com.example.ui.theme.MonoAddressStyle

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ServerEditorSheet(
    editingServer: DnsServerEntity?,
    onDismiss: () -> Unit,
    onSave: (name: String, address: String, mode: TransportMode) -> Boolean
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var address by remember(editingServer) { mutableStateOf(editingServer?.address.orEmpty()) }
    var name by remember(editingServer) {
        mutableStateOf(
            if (editingServer != null && editingServer.name != editingServer.address) {
                editingServer.name
            } else {
                ""
            }
        )
    }
    var selectedMode by remember(editingServer) {
        mutableStateOf(TransportMode.fromName(editingServer?.transportMode))
    }
    var showValidationError by remember { mutableStateOf(false) }

    val parsedPreview by remember(address) {
        derivedStateOf { AddressValidator.parse(address) }
    }
    val statusColors = LocalPulsStatusColors.current

    val submitAction = {
        if (parsedPreview != null && onSave(name, address, selectedMode)) {
            onDismiss()
        } else {
            showValidationError = true
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(
                    if (editingServer == null) R.string.action_add_server else R.string.action_edit_server
                ),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = address,
                    onValueChange = {
                        address = it
                        showValidationError = false
                    },
                    label = { Text(stringResource(R.string.field_address_label)) },
                    placeholder = { Text(stringResource(R.string.field_address_placeholder)) },
                    singleLine = true,
                    isError = showValidationError && parsedPreview == null,
                    textStyle = MonoAddressStyle,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("server_address_input")
                )

                val helperText = when {
                    address.isBlank() && showValidationError -> stringResource(R.string.error_empty_address)
                    parsedPreview == null && showValidationError -> stringResource(R.string.error_invalid_address)
                    parsedPreview != null -> "Распознано: ${parsedPreview!!.kind.hint} (${parsedPreview!!.host})"
                    else -> "Поддерживаются IPv4, домены (xbox-dns.ru), https:// и tls://"
                }
                val helperColor = when {
                    showValidationError && parsedPreview == null -> statusColors.danger
                    parsedPreview != null -> statusColors.active
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(
                    text = helperText,
                    style = MaterialTheme.typography.bodySmall,
                    color = helperColor
                )
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.field_name_label)) },
                placeholder = { Text(stringResource(R.string.field_name_placeholder)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submitAction() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("server_name_input")
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.field_transport_label),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TransportMode.entries.forEach { mode ->
                        FilterChip(
                            selected = selectedMode == mode,
                            onClick = { selectedMode = mode },
                            label = { Text(mode.label) },
                            modifier = Modifier.testTag("transport_chip_${mode.name.lowercase()}")
                        )
                    }
                }
                Text(
                    text = when (selectedMode) {
                        TransportMode.AUTO -> "Автоматически определит DoH, DoT или резолвит домен в IP (UDP 53)."
                        TransportMode.UDP -> "Классический DNS по UDP на порт 53 (домен резолвится в IPv4)."
                        TransportMode.DOH -> "Шифрованный DNS поверх HTTPS (порт 443)."
                        TransportMode.DOT -> "Шифрованный DNS поверх TLS (порт 853)."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("cancel_server_button")
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
                Button(
                    onClick = submitAction,
                    modifier = Modifier.testTag("save_server_button")
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }
}
