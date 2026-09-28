package dev.opencode.android.qr

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.opencode.android.feature.servers.camera.QrCodeDecoder
import javax.inject.Singleton

/**
 * The Play flavor's QR decoder binding: ML Kit with its bundled model.
 *
 * The flavor split lives here rather than in `feature:servers` so the F-Droid build never pulls
 * ML Kit, or anything else that needs Play Services, into the APK (plan §3).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PlayQrCodeModule {

    @Binds
    @Singleton
    abstract fun bindQrCodeDecoder(decoder: MlKitQrCodeDecoder): QrCodeDecoder
}
