package com.kobe.camscanner.navigation

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kobe.camscanner.core.ui.theme.KobeMotion
import com.kobe.camscanner.feature.annotate.AnnotateScreen
import com.kobe.camscanner.feature.camera.CameraScreen
import com.kobe.camscanner.feature.crop.CropScreen
import com.kobe.camscanner.feature.document.DocumentScreen
import com.kobe.camscanner.feature.filter.FilterScreen
import com.kobe.camscanner.feature.home.HomeScreen
import com.kobe.camscanner.feature.library.LibraryScreen
import com.kobe.camscanner.feature.pages.ReviewScreen
import com.kobe.camscanner.feature.search.SearchScreen
import com.kobe.camscanner.feature.settings.SettingsScreen
import com.kobe.camscanner.feature.watermark.WatermarkScreen

/**
 * The whole navigation graph.
 *
 * Two transition families: library-style screens slide horizontally, and anything that takes over
 * the screen to work on an image — camera, crop, filter, annotate — rises from the bottom. The
 * distinction is not decoration; it tells the user whether they have moved sideways in the app or
 * dropped into a task they will come back from.
 */
@Composable
fun KobeNavHost(
    navController: NavHostController = rememberNavController(),
    startDestination: String = Routes.HOME,
    pendingImport: List<android.net.Uri> = emptyList(),
    onImportConsumed: () -> Unit = {},
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = {
            slideInHorizontally(tween(KobeMotion.Normal, easing = KobeMotion.Standard)) { it / 6 } +
                fadeIn(tween(KobeMotion.Normal))
        },
        exitTransition = {
            slideOutHorizontally(tween(KobeMotion.Normal, easing = KobeMotion.Standard)) { -it / 8 } +
                fadeOut(tween(KobeMotion.Fast))
        },
        popEnterTransition = {
            slideInHorizontally(tween(KobeMotion.Normal, easing = KobeMotion.Standard)) { -it / 8 } +
                fadeIn(tween(KobeMotion.Normal))
        },
        popExitTransition = {
            slideOutHorizontally(tween(KobeMotion.Normal, easing = KobeMotion.Standard)) { it / 6 } +
                fadeOut(tween(KobeMotion.Fast))
        },
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                pendingImport = pendingImport,
                onImportConsumed = onImportConsumed,
                onScan = { navController.navigate(Routes.CAMERA) },
                onReview = { navController.navigate(Routes.REVIEW) },
                onOpenDocument = { navController.navigate(Routes.document(it)) },
                onOpenLibrary = { navController.navigate(Routes.LIBRARY) },
                onOpenFolder = { navController.navigate(Routes.folder(it)) },
                onOpenSearch = { navController.navigate(Routes.SEARCH) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(Routes.LIBRARY) {
            LibraryScreen(
                onOpenDocument = { navController.navigate(Routes.document(it)) },
                onBack = navController::popBackStack,
                onScan = { navController.navigate(Routes.CAMERA) },
            )
        }

        composable(
            route = Routes.FOLDER,
            arguments = listOf(navArgument(Routes.ARG_FOLDER_ID) { type = NavType.StringType }),
        ) {
            LibraryScreen(
                onOpenDocument = { navController.navigate(Routes.document(it)) },
                onBack = navController::popBackStack,
                onScan = { navController.navigate(Routes.CAMERA) },
            )
        }

        composable(Routes.SEARCH) {
            SearchScreen(
                onOpenDocument = { navController.navigate(Routes.document(it)) },
                onBack = navController::popBackStack,
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = navController::popBackStack)
        }

        taskDestination(Routes.CAMERA) {
            CameraScreen(
                onFinished = {
                    navController.navigate(Routes.REVIEW) {
                        // The viewfinder is left behind on the way to review, so Back from review
                        // returns to wherever the scan started rather than reopening the camera.
                        popUpTo(Routes.CAMERA) { inclusive = true }
                    }
                },
                onClose = navController::popBackStack,
            )
        }

        composable(Routes.REVIEW) {
            ReviewScreen(
                onAddPage = { navController.navigate(Routes.CAMERA) },
                onEditCrop = { navController.navigate(Routes.crop(it)) },
                onEditFilter = { navController.navigate(Routes.filter(it)) },
                onAnnotate = { navController.navigate(Routes.annotate(it)) },
                onSaved = { id ->
                    navController.navigate(Routes.document(id)) {
                        popUpTo(Routes.HOME)
                    }
                },
                onBack = navController::popBackStack,
            )
        }

        taskDestination(
            route = Routes.CROP,
            arguments = listOf(navArgument(Routes.ARG_PAGE_ID) { type = NavType.StringType }),
        ) {
            CropScreen(
                onDone = navController::popBackStack,
                onCancel = navController::popBackStack,
            )
        }

        taskDestination(
            route = Routes.FILTER,
            arguments = listOf(navArgument(Routes.ARG_PAGE_ID) { type = NavType.StringType }),
        ) {
            FilterScreen(
                onDone = navController::popBackStack,
                onCancel = navController::popBackStack,
            )
        }

        taskDestination(
            route = Routes.ANNOTATE,
            arguments = listOf(navArgument(Routes.ARG_PAGE_ID) { type = NavType.StringType }),
        ) {
            AnnotateScreen(
                onDone = navController::popBackStack,
                onCancel = navController::popBackStack,
            )
        }

        composable(
            route = Routes.DOCUMENT,
            arguments = listOf(navArgument(Routes.ARG_DOCUMENT_ID) { type = NavType.StringType }),
        ) {
            DocumentScreen(
                onBack = navController::popBackStack,
                onEditPages = { navController.navigate(Routes.REVIEW) },
                onAddWatermark = { navController.navigate(Routes.watermark(it)) },
            )
        }

        taskDestination(
            route = Routes.WATERMARK,
            arguments = listOf(navArgument(Routes.ARG_DOCUMENT_ID) { type = NavType.StringType }),
        ) {
            WatermarkScreen(
                onDone = navController::popBackStack,
                onCancel = navController::popBackStack,
            )
        }
    }
}

/** A destination that rises from the bottom: the image-editing surfaces. */
private fun androidx.navigation.NavGraphBuilder.taskDestination(
    route: String,
    arguments: List<androidx.navigation.NamedNavArgument> = emptyList(),
    content: @Composable AnimatedContentScope.(androidx.navigation.NavBackStackEntry) -> Unit,
) {
    composable(
        route = route,
        arguments = arguments,
        enterTransition = {
            slideInVertically(tween(KobeMotion.Slow, easing = KobeMotion.Decelerate)) { it / 3 } +
                fadeIn(tween(KobeMotion.Normal))
        },
        exitTransition = { fadeOut(tween(KobeMotion.Fast)) },
        popEnterTransition = { fadeIn(tween(KobeMotion.Normal)) },
        popExitTransition = {
            slideOutVertically(tween(KobeMotion.Normal, easing = KobeMotion.Accelerate)) { it / 3 } +
                fadeOut(tween(KobeMotion.Normal))
        },
        content = content,
    )
}
