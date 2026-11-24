package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.ActiveTransport
import com.example.data.AiAccessStatus
import com.example.data.AiTargets
import com.example.data.AiTestResultEntity
import com.example.data.DnsServerEntity
import com.example.ui.PulsUiState
import com.example.ui.theme.LocalPulsStatusColors
import com.example.ui.theme.MonoAddressStyle
import com.example.ui.theme.MonoMetricStyle

@Composable
fun TesterScreen(
    uiState: PulsUiState,
    onRunTestAll: () -> Unit,
    onStopTesting: () -> Unit,
    onTestSingle: (DnsServerEntity) -> Unit,
    onSelectBest: () -> Unit,
    onUseServer: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val statusColors = LocalPulsStatusColors.current
    val progress = uiState.testerProgress

    // Sort by best tested AI score first, then lowest latency, keeping untested at bottom
    val sortedServers = remember(uiState.servers) {
        uiState.servers.sortedWith(
            compareByDescending<DnsServerEntity> { it.lastAiAvailableCount ?: -1 }
                .thenBy { it.lastLatencyMs ?: Int.MAX_VALUE }
                .thenBy { it.createdAt }
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "tester_header_card") {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, statusColors.hairlineBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.tester_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (progress.isTestingAll) {
                            Button(
                                onClick = onStopTesting,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = statusColors.danger
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("stop_test_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Stop,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.action_stop_test))
                            }
                        } else {
                            Button(
                                onClick = onRunTestAll,
                                enabled = uiState.servers.isNotEmpty(),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("test_all_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.action_test_all))
                            }
                        }

                        OutlinedButton(
                            onClick = onSelectBest,
                            enabled = uiState.bestServerId != null,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("select_best_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.action_select_best))
                        }
                    }

                    AnimatedVisibility(visible = progress.isTestingAll && progress.totalCount > 0) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            LinearProgressIndicator(
                                progress = {
                                    (progress.completedCount.toFloat() / progress.totalCount.coerceAtLeast(1))
                                        .coerceIn(0f, 1f)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(5.dp)
                                    .clip(RoundedCornerShape(99.dp))
                            )
                            Text(
                                text = "Проверено ${progress.completedCount} из ${progress.totalCount}",
                                style = MonoMetricStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        if (sortedServers.isEmpty()) {
            item(key = "tester_empty") {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, statusColors.hairlineBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Dns,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(30.dp)
                        )
                        Text(
                            text = stringResource(R.string.tester_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(
                items = sortedServers,
                key = { it.id }
            ) { server ->
                val results = uiState.testResultsByServer[server.id].orEmpty()
                val isTesting = server.id in progress.testingServerIds
                val isBest = server.id == uiState.bestServerId

                BenchmarkServerCard(
                    server = server,
                    results = results,
                    isTesting = isTesting,
                    isBest = isBest,
                    onRetest = { onTestSingle(server) },
                    onUseServer = { onUseServer(server.id) }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BenchmarkServerCard(
    server: DnsServerEntity,
    results: List<AiTestResultEntity>,
    isTesting: Boolean,
    isBest: Boolean,
    onRetest: () -> Unit,
    onUseServer: () -> Unit
) {
    val statusColors = LocalPulsStatusColors.current
    var expanded by remember { mutableStateOf(false) }

    val resultsById = remember(results) { results.associateBy { it.serviceId } }
    val protocolLabel = ActiveTransport.fromName(server.detectedTransport)?.label

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = if (isBest) 1.5.dp else 1.dp,
            color = if (isBest) statusColors.active.copy(alpha = 0.55f) else statusColors.hairlineBorder
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable { expanded = !expanded }
            .testTag("benchmark_card_${server.id}")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = server.address,
                            style = MonoAddressStyle,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (isBest) {
                            CompactBadge(
                                text = "Лучший",
                                containerColor = statusColors.activeContainer,
                                contentColor = statusColors.active
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (protocolLabel != null) {
                            CompactBadge(
                                text = protocolLabel,
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (server.lastLatencyMs != null) {
                            CompactBadge(
                                text = "${server.lastLatencyMs} мс",
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (server.lastAiAvailableCount != null) {
                            val avail = server.lastAiAvailableCount
                            val total = server.lastAiTotalCount
                            val (bg, fg) = when {
                                avail >= 3 -> statusColors.activeContainer to statusColors.active
                                avail >= 1 -> statusColors.warningContainer to statusColors.warning
                                else -> statusColors.dangerContainer to statusColors.danger
                            }
                            CompactBadge(
                                text = "$avail/$total AI",
                                containerColor = bg,
                                contentColor = fg
                            )
                        } else if (!isTesting) {
                            Text(
                                text = "Не проверен",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (isTesting) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(48.dp)
                    ) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                } else {
                    IconButton(
                        onClick = onRetest,
                        modifier = Modifier.testTag("retest_server_${server.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = stringResource(R.string.action_test_single),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = stringResource(R.string.cd_expand_details),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 4 AI Target Chips
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AiTargets.defaultTargets.forEach { target ->
                    val entry = resultsById[target.id]
                    val status = entry?.let { AiAccessStatus.fromName(it.status) }
                    AiServiceStatusPill(
                        name = target.name,
                        status = status
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    HorizontalDivider(color = statusColors.hairlineBorder)
                    Spacer(modifier = Modifier.height(10.dp))

                    if (results.isEmpty()) {
                        Text(
                            text = "Нажмите кнопку обновления, чтобы выполнить замер DNS и TLS для этого сервера.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            AiTargets.defaultTargets.forEach { target ->
                                val item = resultsById[target.id]
                                if (item != null) {
                                    AiDetailRow(item)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = onUseServer,
                            modifier = Modifier.testTag("use_tested_server_${server.id}")
                        ) {
                            Text(
                                text = if (server.isSelected) "Выбран активным" else "Выбрать этот DNS"
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AiServiceStatusPill(
    name: String,
    status: AiAccessStatus?
) {
    val statusColors = LocalPulsStatusColors.current
    val dotColor = when (status) {
        AiAccessStatus.AVAILABLE -> statusColors.active
        AiAccessStatus.RESTRICTED -> statusColors.warning
        AiAccessStatus.UNREACHABLE -> statusColors.danger
        null -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Text(
                text = name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun AiDetailRow(item: AiTestResultEntity) {
    val statusColors = LocalPulsStatusColors.current
    val status = AiAccessStatus.fromName(item.status)
    val accent = when (status) {
        AiAccessStatus.AVAILABLE -> statusColors.active
        AiAccessStatus.RESTRICTED -> statusColors.warning
        AiAccessStatus.UNREACHABLE -> statusColors.danger
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${item.serviceName} · ${item.domain}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = item.detailMessage,
                style = MaterialTheme.typography.bodySmall,
                color = accent
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = item.resolvedIp ?: "—",
                style = MonoMetricStyle,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = item.dnsLatencyMs?.let { "$it мс" } ?: "—",
                style = MonoMetricStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
