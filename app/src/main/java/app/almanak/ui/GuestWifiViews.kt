package app.almanak.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.almanak.R
import app.almanak.settings.GuestWifi
import app.almanak.settings.WifiSecurity
import app.almanak.ui.theme.LocalAlmanakTheme

/**
 * The guest network as a join QR, bottom-right. Nothing shows at rest: it appears on an arrow press and leaves on
 * its own (MainActivity decides when), so it never sits on the panel long enough to mark it.
 */
@Composable
fun GuestWifiCard(wifi: GuestWifi, visible: Boolean) {
    Box(Modifier.fillMaxSize().padding(end = 64.dp, bottom = 56.dp), contentAlignment = Alignment.BottomEnd) {
        AnimatedVisibility(visible && wifi.configured, enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier.background(LocalAlmanakTheme.current.background.copy(alpha = 0.92f), RoundedCornerShape(16.dp)).padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                QrCode(wifi.qrPayload(), 200.dp)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.guest_wifi), style = Type.serifItalic(30.sp, Fg))
                    Text(wifi.ssid, style = Type.number(22.sp))
                    // Typed by hand on laptops (no camera), so the password is spelled out.
                    Text(
                        if (wifi.security == WifiSecurity.None) stringResource(R.string.guest_wifi_open)
                        else stringResource(R.string.guest_wifi_password, wifi.password),
                        style = Type.label(16.sp),
                    )
                    Text(stringResource(R.string.guest_wifi_scan), Modifier.padding(top = 10.dp), style = Type.label(13.sp, FgFaint))
                }
            }
        }
    }
}
