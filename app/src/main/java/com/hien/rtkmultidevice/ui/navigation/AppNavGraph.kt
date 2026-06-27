package com.hien.rtkmultidevice.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.compose.runtime.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hien.rtkmultidevice.ui.screens.connection.ConnectionScreen
import com.hien.rtkmultidevice.ui.screens.coordsettings.CoordSettingsScreen
import com.hien.rtkmultidevice.ui.screens.gnss.GnssScreen
import com.hien.rtkmultidevice.ui.screens.main.MainScreen
import com.hien.rtkmultidevice.ui.screens.ntrip.NtripConfigScreen
import com.hien.rtkmultidevice.ui.screens.project.ProjectScreen
import com.hien.rtkmultidevice.ui.screens.map.MapScreen
import com.hien.rtkmultidevice.ui.screens.stakeout.StakeoutScreen
import com.hien.rtkmultidevice.ui.screens.survey.SurveyScreen
import com.hien.rtkmultidevice.ui.screens.traverse.TraverseScreen
import com.hien.rtkmultidevice.ui.screens.gnss.GnssViewModel

/**
 * AppNavGraph — Định nghĩa luồng điều hướng của ứng dụng.
 *
 * Luồng chính:
 *   ConnectionScreen → ProjectScreen (kết nối thành công)
 *   ProjectScreen → GnssScreen (chọn / tạo dự án)
 *   GnssScreen → NtripConfigScreen → GnssScreen
 *   GnssScreen → CoordSettingsScreen → GnssScreen   ← Phase 4
 *   GnssScreen → SurveyScreen                       ← Phase 5
 *   GnssScreen → StakeoutScreen                     ← Phase 6
 *   GnssScreen → MapScreen                          ← Phase 7
 */
@Composable
fun AppNavGraph(navController: NavHostController) {
    NavHost(
        navController    = navController,
        startDestination = Screen.Connection.route
    ) {

        // ── Màn hình 1: Kết nối thiết bị ──────────────────
        composable(Screen.Connection.route) {
            ConnectionScreen(
                onConnected = {
                    navController.navigate(Screen.Main.route) {
                        popUpTo(Screen.Connection.route) { inclusive = true }
                    }
                },
                onSkip = {
                    // Bỏ qua kết nối RTK → vào Main để dùng Stakeout/Survey offline
                    navController.navigate(Screen.Main.route) {
                        popUpTo(Screen.Connection.route) { inclusive = true }
                    }
                }
            )
        }

        // ── Màn hình chính 4 Tab (Phase 9) ────────────────
        composable(Screen.Main.route) {
            val gnssVm: GnssViewModel = hiltViewModel()
            val connectionState by gnssVm.connectionState.collectAsStateWithLifecycle()
            val activeProjectId by gnssVm.activeProjectId.collectAsStateWithLifecycle()

            MainScreen(
                activeProjectId    = activeProjectId,
                connectionState    = connectionState,
                onNavigateConnect  = { navController.navigate(Screen.Connection.route) },
                onNavigateGnss     = { navController.navigate(Screen.Gnss.route) },
                onNavigateNtrip    = { navController.navigate(Screen.NtripConfig.route) },
                onNavigateProject  = { navController.navigate(Screen.Project.route) },
                onNavigateSurvey   = { pId -> navController.navigate(Screen.Survey.createRoute(pId)) },
                onNavigateStakeout  = { pId -> navController.navigate(Screen.Stakeout.createRoute(pId)) },
                onNavigateTraverse  = { pId -> navController.navigate(Screen.Traverse.createRoute(pId)) },
                onNavigateMap       = { pId -> navController.navigate(Screen.Map.createRoute(pId)) },
                onNavigateCoord    = { navController.navigate(Screen.CoordSettings.route) }
            )
        }

        // ── Màn hình Danh sách dự án ───────────────────
        composable(Screen.Project.route) {
            ProjectScreen(
                onProjectSelected = { projectId ->
                    navController.popBackStack()
                }
            )
        }

        // ── Màn hình 3: Dữ liệu GNSS ──────────────────────
        composable(Screen.Gnss.route) {
            GnssScreen(
                onNavigateToNtripConfig = {
                    navController.navigate(Screen.NtripConfig.route)
                },
                onNavigateToCoordSettings = {
                    navController.navigate(Screen.CoordSettings.route)
                },
                onNavigateToSurvey = { projectId ->
                    navController.navigate(Screen.Survey.createRoute(projectId))
                },
                onNavigateToStakeout = { projectId ->
                    navController.navigate(Screen.Stakeout.createRoute(projectId))
                },
                onNavigateToMap      = { projectId ->
                    navController.navigate(Screen.Map.createRoute(projectId))
                },
                onDisconnect = {
                    // Ngắt kết nối → quay về Main (tab Thiết bị để kết nối lại)
                    navController.popBackStack()
                }
            )
        }

        // ── Màn hình 4: Cấu hình NTRIP ────────────────────
        composable(Screen.NtripConfig.route) {
            NtripConfigScreen(
                onSaved = { navController.popBackStack() }
            )
        }

        // ── Màn hình 5: Cài đặt toạ độ VN-2000 ───────────
        composable(Screen.CoordSettings.route) {
            CoordSettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // ── Màn hình 6: Thu thập điểm đo (Phase 5.3) ─────
        composable(
            route     = Screen.Survey.route,
            arguments = listOf(navArgument("projectId") { type = NavType.IntType })
        ) { backStackEntry ->
            val pId = backStackEntry.arguments?.getInt("projectId") ?: -1
            SurveyScreen(
                projectId      = pId,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // ── Màn hình 7: Cắm mốc Stakeout (Phase 6) ───────
        // Hỗ trợ optional args: targetN, targetE, targetName (từ MapScreen "Cắm mốc")
        composable(
            route     = Screen.Stakeout.route,
            arguments = listOf(
                navArgument("projectId") { type = NavType.IntType },
                navArgument("targetN")    { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("targetE")    { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("targetName") { type = NavType.StringType; nullable = true; defaultValue = null }
            )
        ) { backStackEntry ->
            val pId = backStackEntry.arguments?.getInt("projectId") ?: -1
            StakeoutScreen(
                projectId      = pId,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // ── Màn hình Traverse (Phase 5.5) ─────────────────
        composable(
            route     = Screen.Traverse.route,
            arguments = listOf(navArgument("projectId") { type = NavType.IntType })
        ) { backStackEntry ->
            val pId = backStackEntry.arguments?.getInt("projectId") ?: -1
            TraverseScreen(
                projectId      = pId,
                onNavigateBack = { navController.popBackStack() },
                onNavigateStakeout = {
                    runCatching { navController.navigate(Screen.Stakeout.createRoute(pId)) }
                        .onFailure { android.util.Log.e("AppNavGraph", "Nav Stakeout lỗi: ${it.message}") }
                }
            )
        }

        // ── Màn hình 8: Bản đồ OSM (Phase 7) ─────────────
        composable(
            route     = Screen.Map.route,
            arguments = listOf(navArgument("projectId") { type = NavType.IntType })
        ) { backStackEntry ->
            val pId = backStackEntry.arguments?.getInt("projectId") ?: -1
            MapScreen(
                projectId           = pId,
                onNavigateBack      = { navController.popBackStack() },
                onNavigateStakeout  = { _, _, _ ->
                    // Target đã được đặt vào StakeoutTargetHolder (MapViewModel.prepareStakeout)
                    // → chỉ cần navigate route đơn giản, KHÔNG truyền toạ độ/tên qua route string
                    //   (tên điểm chứa ký tự đặc biệt từng làm route không khớp → crash).
                    // runCatching: phòng thủ tuyệt đối — không bao giờ crash vì navigation.
                    runCatching {
                        navController.navigate(Screen.Stakeout.createRoute(pId))
                    }.onFailure {
                        android.util.Log.e("AppNavGraph", "Navigate Stakeout lỗi: ${it.message}")
                    }
                }
            )
        }
    }
}
