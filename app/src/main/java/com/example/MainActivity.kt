package com.example

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.DnsServerEntity
import com.example.ui.DnsVpnViewModel
import com.example.ui.components.ServerEditorSheet
import com.example.ui.screens.TesterScreen
import com.example.ui.screens.VpnScreen
import com.example.ui.theme.PulsTheme
import com.example.vpn.VpnConnectionStatus

enum class PulsDestination(val route: String) {
    VPN("vpn"),
    TESTER("tester")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PulsTheme {
                PulsApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PulsApp(
    viewModel: DnsVpnViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var currentTab by rememberSaveable { mutableStateOf(PulsDestination.VPN) }
    var showServerSheet by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf<DnsServerEntity?>(null) }

    val vpnPermissionDeniedText = stringResource(R.string.error_vpn_permission_denied)

    val vpnConsentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.startVpnWithSelectedServer()
        } else {
            viewModel.showMessage(vpnPermissionDeniedText)
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        // Proceed with VPN flow regardless of notification permission result
        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent != null) {
            vpnConsentLauncher.launch(prepareIntent)
        } else {
            viewModel.startVpnWithSelectedServer()
        }
    }

    val requestStartOrStopVpn = {
        when (uiState.vpnState.status) {
            VpnConnectionStatus.CONNECTED,
            VpnConnectionStatus.CONNECTING -> viewModel.stopVpn()

            VpnConnectionStatus.DISCONNECTED,
            VpnConnectionStatus.ERROR -> {
                val needsNotificationPermission =
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED

                if (needsNotificationPermission) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    val prepareIntent = VpnService.prepare(context)
                    if (prepareIntent != null) {
                        vpnConsentLauncher.launch(prepareIntent)
                    } else {
                        viewModel.startVpnWithSelectedServer()
                    }
                }
            }
        }
    }

    LaunchedEffect(uiState.snackbarMessage) {
        val message = uiState.snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearSnackbar()
    }

    BackHandler(enabled = currentTab != PulsDestination.VPN) {
        currentTab = PulsDestination.VPN
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavRail = maxWidth >= 600.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = when (currentTab) {
                                PulsDestination.VPN -> stringResource(R.string.app_name)
                                PulsDestination.TESTER -> stringResource(R.string.tab_tester)
                            },
                            style = MaterialTheme.typography.displaySmall
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground
                    )
                )
            },
            floatingActionButton = {
                if (currentTab == PulsDestination.VPN) {
                    FloatingActionButton(
                        onClick = {
                            editingServer = null
                            showServerSheet = true
                        },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.testTag("add_server_fab")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = stringResource(R.string.cd_add_server)
                        )
                    }
                }
            },
            bottomBar = {
                if (!useNavRail) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        NavigationBarItem(
                            selected = currentTab == PulsDestination.VPN,
                            onClick = { currentTab = PulsDestination.VPN },
                            icon = {
                                Icon(
                                    imageVector = if (currentTab == PulsDestination.VPN) {
                                        Icons.Filled.Shield
                                    } else {
                                        Icons.Outlined.Shield
                                    },
                                    contentDescription = stringResource(R.string.tab_vpn)
                                )
                            },
                            label = { Text(stringResource(R.string.tab_vpn)) },
                            modifier = Modifier.testTag("nav_tab_vpn")
                        )
                        NavigationBarItem(
                            selected = currentTab == PulsDestination.TESTER,
                            onClick = { currentTab = PulsDestination.TESTER },
                            icon = {
                                Icon(
                                    imageVector = if (currentTab == PulsDestination.TESTER) {
                                        Icons.Filled.Speed
                                    } else {
                                        Icons.Outlined.Speed
                                    },
                                    contentDescription = stringResource(R.string.tab_tester)
                                )
                            },
                            label = { Text(stringResource(R.string.tab_tester)) },
                            modifier = Modifier.testTag("nav_tab_tester")
                        )
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavRail) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        NavigationRailItem(
                            selected = currentTab == PulsDestination.VPN,
                            onClick = { currentTab = PulsDestination.VPN },
                            icon = {
                                Icon(
                                    imageVector = if (currentTab == PulsDestination.VPN) {
                                        Icons.Filled.Shield
                                    } else {
                                        Icons.Outlined.Shield
                                    },
                                    contentDescription = stringResource(R.string.tab_vpn)
                                )
                            },
                            label = { Text(stringResource(R.string.tab_vpn)) },
                            modifier = Modifier.testTag("nav_rail_vpn")
                        )
                        NavigationRailItem(
                            selected = currentTab == PulsDestination.TESTER,
                            onClick = { currentTab = PulsDestination.TESTER },
                            icon = {
                                Icon(
                                    imageVector = if (currentTab == PulsDestination.TESTER) {
                                        Icons.Filled.Speed
                                    } else {
                                        Icons.Outlined.Speed
                                    },
                                    contentDescription = stringResource(R.string.tab_tester)
                                )
                            },
                            label = { Text(stringResource(R.string.tab_tester)) },
                            modifier = Modifier.testTag("nav_rail_tester")
                        )
                    }
                }

                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    val contentModifier = Modifier
                        .widthIn(max = 720.dp)
                        .fillMaxSize()

                    when (currentTab) {
                        PulsDestination.VPN -> {
                            VpnScreen(
                                uiState = uiState,
                                onToggleVpn = requestStartOrStopVpn,
                                onSelectServer = viewModel::selectServer,
                                onEditServer = { server ->
                                    editingServer = server
                                    showServerSheet = true
                                },
                                onDeleteServer = viewModel::deleteServer,
                                onTestSingleServer = { server ->
                                    viewModel.runTestSingle(server)
                                    currentTab = PulsDestination.TESTER
                                },
                                modifier = contentModifier
                            )
                        }

                        PulsDestination.TESTER -> {
                            TesterScreen(
                                uiState = uiState,
                                onRunTestAll = viewModel::runTestAll,
                                onStopTesting = viewModel::stopTesting,
                                onTestSingle = viewModel::runTestSingle,
                                onSelectBest = viewModel::selectBestServer,
                                onUseServer = { serverId ->
                                    viewModel.selectServer(serverId)
                                    currentTab = PulsDestination.VPN
                                },
                                modifier = contentModifier
                            )
                        }
                    }
                }
            }
        }
    }

    if (showServerSheet) {
        ServerEditorSheet(
            editingServer = editingServer,
            onDismiss = {
                showServerSheet = false
                editingServer = null
            },
            onSave = { name, address, mode ->
                val target = editingServer
                if (target == null) {
                    viewModel.addServer(name, address, mode)
                } else {
                    viewModel.updateServer(target, name, address, mode)
                }
            }
        )
    }
}
