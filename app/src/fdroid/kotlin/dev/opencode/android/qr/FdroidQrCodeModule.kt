package dev.opencode.android.qr

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.opencode.android.feature.servers.camera.QrCodeDecoder
import javax.inject.Singleton

/** The F-Droid flavor's QR decoder binding: ZXing, with no Google Play Services anywhere. */
@Module
@InstallIn(SingletonComponent::class)
abstract class FdroidQrCodeModule {

    @Binds
    @Singleton
    abstract fun bindQrCodeDecoder(decoder: ZxingQrCodeDecoder): QrCodeDecoder
}
