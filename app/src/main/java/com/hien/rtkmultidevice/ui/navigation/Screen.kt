package com.hien.rtkmultidevice.ui.navigation

/**
 * Screen — Định nghĩa tất cả màn hình trong ứng dụng.
 *
 * sealed class + object → type-safe route names.
 * Tránh lỗi typo khi navigate (VD: "gnss" vs "Gnss").
 *
 * Route là chuỗi định danh màn hình trong Navigation Compose,
 * giống như URL trong web app.
 */
sealed class Screen(val route: String) {

    /** Màn hình kết nối thiết bị (màn hình đầu tiên) */
    data object Connection : Screen("connection")

    /** Màn hình hiển thị dữ liệu GNSS live */
    data object Gnss : Screen("gnss")

    /** Màn hình cấu hình NTRIP */
    data object NtripConfig : Screen("ntrip_config")

    /** Màn hình cài đặt toạ độ VN-2000 (Phase 4) */
    data object CoordSettings : Screen("coord_settings")

    data object BaseConfig : Screen("base_config")

    /** Màn hình danh sách / chọn dự án (Phase 5) */
    data object Project : Screen("project")

    /** Màn hình thu thập điểm đo RTK (Phase 5) */
    data object Survey : Screen("survey/{projectId}") {
        fun createRoute(projectId: Int) = "survey/$projectId"
    }

    /**
     * Màn hình cắm mốc Stakeout (Phase 6).
     *
     * Route bắt buộc: projectId
     * Route tùy chọn: targetN, targetE, targetName  ← truyền từ MapScreen khi bấm "Cắm mốc"
     *
     * Ví dụ:
     *   createRoute(1)                          → "stakeout/1"  (chỉ mở, không set target)
     *   createRoute(1, 1186788.0, 565432.0, "A1") → "stakeout/1?targetN=1186788.0&targetE=565432.0&targetName=A1"
     */
    data object Stakeout : Screen("stakeout/{projectId}?targetN={targetN}&targetE={targetE}&targetName={targetName}") {
        /** Mở Stakeout không kèm target (từ MainScreen / GnssScreen) */
        fun createRoute(projectId: Int) = "stakeout/$projectId"

        /** Mở Stakeout và đặt target ngay lập tức (từ MapScreen → tap vector feature) */
        fun createRoute(
            projectId  : Int,
            targetN    : Double,
            targetE    : Double,
            targetName : String
        ) = "stakeout/$projectId?targetN=$targetN&targetE=$targetE&targetName=${
            java.net.URLEncoder.encode(targetName, "UTF-8")
        }"
    }

    /** Màn hình bản đồ OSM (Phase 7) */
    data object Map : Screen("map/{projectId}") {
        fun createRoute(projectId: Int) = "map/$projectId"
    }

    /** Màn hình chính 4 tab (Phase 9) */
    data object Main : Screen("main")

    /** Màn hình đo tuyến Traverse (Phase 5.5) */
    data object Traverse : Screen("traverse/{projectId}") {
        fun createRoute(projectId: Int) = "traverse/$projectId"
    }
}
