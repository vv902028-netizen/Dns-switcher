package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.ActiveTransport
import com.example.data.DnsServerEntity
import com.example.data.TransportMode
import com.example.ui.PulsUiState
import com.example.ui.theme.LocalPulsStatusColors
import com.example.ui.theme.MonoAddressStyle
import com.example.ui.theme.MonoMetricStyle
import com.example.vpn.VpnConnectionStatus
import com.example.vpn.VpnRuntimeState
import kotlinx.coroutines.delay

@Composable
fun VpnScreen(
    uiState: PulsUiState,
    onToggleVpn: () -> Unit,
    onSelectServer: (Long) -> Unit,
    onEditServer: (DnsServerEntity) -> Unit,
    onDeleteServer: (DnsServerEntity) -> Unit,
    onTestSingleServer: (DnsServerEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "hero_connection_card") {
            ConnectionHeroCard(
                vpnState = uiState.vpnState,
                selectedServer = uiState.selectedServer,
                onToggleVpn = onToggleVpn
            )
        }

        item(key = "servers_section_header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.section_servers),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Surface(
                    shape = RoundedCornerShape(99.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = "${uiState.servers.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }

        if (uiState.servers.isEmpty()) {
            item(key = "servers_empty_state") {
                EmptyServersCard()
            }
        } else {
            items(
                items = uiState.servers,
                key = { it.id }
            ) { server ->
                val isCurrentlyRouted = uiState.vpnState.status == VpnConnectionStatus.CONNECTED &&
                    uiState.vpnState.activeServerId == server.id
                val isTesting = server.id in uiState.testerProgress.testingServerIds

                DnsServerRowCard(
                    server = server,
                    isCurrentlyRouted = isCurrentlyRouted,
                    isTesting = isTesting,
                    onClick = { onSelectServer(server.id) },
                    onTest = { onTestSingleServer(server) },
                    onEdit = { onEditServer(server) },
                    onDelete = { onDeleteServer(server) }
                )
            }
        }
    }
}

@Composable
private fun ConnectionHeroCard(
    vpnState: VpnRuntimeState,
    selectedServer: DnsServerEntity?,
    onToggleVpn: () -> Unit
) {
    val statusColors = LocalPulsStatusColors.current
    val isConnected = vpnState.status == VpnConnectionStatus.CONNECTED
    val isConnecting = vpnState.status == VpnConnectionStatus.CONNECTING
    val isError = vpnState.status == VpnConnectionStatus.ERROR

    val accentColor by animateColorAsState(
        targetValue = when (vpnState.status) {
            VpnConnectionStatus.CONNECTED -> statusColors.active
            VpnConnectionStatus.CONNECTING -> statusColors.warning
            VpnConnectionStatus.ERROR -> statusColors.danger
            VpnConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(250),
        label = "accentColor"
    )

    val ringScale by animateFloatAsState(
        targetValue = if (isConnected) 1.03f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "ringScale"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "pulseTransition")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.28f,
        targetValue = 0.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(isConnected, vpnState.connectedSinceMs) {
        while (isConnected && vpnState.connectedSinceMs != null) {
            nowMs = System.currentTimeMillis()
            delay(1000L)
        }
    }

    val toggleDescription = stringResource(R.string.cd_toggle_vpn)

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 1.dp,
            color = if (isConnected) accentColor.copy(alpha = 0.45f) else statusColors.hairlineBorder
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    if (isConnected || isConnecting) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    accentColor.copy(alpha = if (isConnected) pulseAlpha else 0.14f),
                                    Color.Transparent
                                ),
                                center = center.copy(y = size.height * 0.34f),
                                radius = size.minDimension * 0.72f
                            )
                        )
                    }
                }
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Tactile Power Control
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(104.dp)
                    .scale(ringScale)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = if (isConnected) 0.16f else 0.08f))
                    .clickable(
                        role = Role.Switch,
                        onClick = onToggleVpn
                    )
                    .semantics { contentDescription = toggleDescription }
                    .testTag("vpn_power_button")
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (isConnected) accentColor else MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(
                        width = 1.5.dp,
                        color = accentColor.copy(alpha = if (isConnected) 1f else 0.45f)
                    ),
                    modifier = Modifier.size(76.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (isConnecting) {
                            CircularProgressIndicator(
                                strokeWidth = 3.dp,
                                color = accentColor,
                                modifier = Modifier.size(30.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.PowerSettingsNew,
                                contentDescription = null,
                                tint = if (isConnected) Color.White else accentColor,
                                modifier = Modifier.size(34.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = when (vpnState.status) {
                    VpnConnectionStatus.CONNECTED -> stringResource(R.string.status_connected)
                    VpnConnectionStatus.CONNECTING -> stringResource(R.string.status_connecting)
                    VpnConnectionStatus.ERROR -> stringResource(R.string.status_error)
                    VpnConnectionStatus.DISCONNECTED -> stringResource(R.string.status_disconnected)
                },
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("vpn_status_text")
            )

            Spacer(modifier = Modifier.height(4.dp))

            val displayAddress = vpnState.activeAddress ?: selectedServer?.address ?: "Сервер не выбран"
            Text(
                text = displayAddress,
                style = MonoAddressStyle,
                color = if (isConnected) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            AnimatedVisibility(visible = isError && !vpnState.errorMessage.isNullOrBlank()) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = statusColors.dangerContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = statusColors.danger,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = vpnState.errorMessage.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            AnimatedVisibility(visible = isConnected) {
                val uptimeSec = vpnState.connectedSinceMs?.let { ((nowMs - it) / 1000L).coerceAtLeast(0L) } ?: 0L
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MetricCell(
                        label = stringResource(R.string.label_protocol),
                        value = vpnState.activeTransport?.label ?: "—"
                    )
                    MetricCell(
                        label = stringResource(R.string.label_resolved_ip),
                        value = vpnState.resolvedIp ?: "—"
                    )
                    MetricCell(
                        label = stringResource(R.string.label_queries),
                        value = vpnState.queriesHandled.toString()
                    )
                    MetricCell(
                        label = stringResource(R.string.label_uptime),
                        value = formatUptime(uptimeSec)
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricCell(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MonoMetricStyle,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}

@Composable
private fun DnsServerRowCard(
    server: DnsServerEntity,
    isCurrentlyRouted: Boolean,
    isTesting: Boolean,
    onClick: () -> Unit,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val statusColors = LocalPulsStatusColors.current
    var menuExpanded by remember { mutableStateOf(false) }

    val borderColor = when {
        isCurrentlyRouted -> statusColors.active.copy(alpha = 0.55f)
        server.isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        else -> statusColors.hairlineBorder
    }

    val protocolLabel = ActiveTransport.fromName(server.detectedTransport)?.label
        ?: TransportMode.fromName(server.transportMode).label

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(if (server.isSelected) 1.5.dp else 1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .testTag("server_card_${server.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (server.isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = null,
                tint = when {
                    isCurrentlyRouted -> statusColors.active
                    server.isSelected -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                },
                modifier = Modifier.size(22.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.address,
                    style = MonoAddressStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                val subtitleParts = buildList {
                    if (server.name != server.address) add(server.name)
                    if (!server.resolvedIp.isNullOrBlank() && server.resolvedIp != server.address) {
                        add(server.resolvedIp)
                    }
                }
                if (subtitleParts.isNotEmpty()) {
                    Text(
                        text = subtitleParts.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CompactBadge(
                        text = protocolLabel,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (isTesting) {
                        CompactBadge(
                            text = "Проверка…",
                            containerColor = statusColors.warningContainer,
                            contentColor = statusColors.warning
                        )
                    } else if (server.lastAiAvailableCount != null) {
                        val avail = server.lastAiAvailableCount
                        val total = server.lastAiTotalCount
                        val (bg, fg) = when {
                            avail >= 3 -> statusColors.activeContainer to statusColors.active
                            avail >= 1 -> statusColors.warningContainer to statusColors.warning
                            else -> statusColors.dangerContainer to statusColors.danger
                        }
                        val pingPart = server.lastLatencyMs?.let { " · ${it}мс" }.orEmpty()
                        CompactBadge(
                            text = "$avail/$total AI$pingPart",
                            containerColor = bg,
                            contentColor = fg
                        )
                    }
                }
            }

            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.testTag("server_menu_${server.id}")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = stringResource(R.string.cd_server_menu),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_test_single)) },
                        leadingIcon = { Icon(Icons.Outlined.Speed, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onTest()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_edit)) },
                        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete)) },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = null,
                                tint = statusColors.danger
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun CompactBadge(
    text: String,
    containerColor: Color,
    contentColor: Color
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = containerColor
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun EmptyServersCard() {
    val statusColors = LocalPulsStatusColors.current
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
                modifier = Modifier.size(32.dp)
            )
            Text(
                text = stringResource(R.string.servers_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatUptime(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
