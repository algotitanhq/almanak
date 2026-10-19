package app.almanak.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.almanak.R
import app.almanak.ui.theme.LocalAlmanakTheme
import app.almanak.web.SetupApi
import app.almanak.web.SetupServer

/** Debug builds only (`--es setupUrl …`): the address shown in place of the real one, for README captures. */
internal var debugSetupUrl: String? = null

/**
 * The phone setup page's address (with its one-time token) while the caller is on screen: the server runs only
 * then. Null without a LAN address. Started on creation so the port, and therefore the QR, is known in the first frame.
 */
@Composable
fun rememberSetupUrl(api: SetupApi): String? {
    val server = remember { SetupServer(api::handle).also { it.start() } }
    DisposableEffect(server) { onDispose { server.stop() } }
    return debugSetupUrl ?: remember(server) { SetupServer.lanAddress()?.let { "http://$it:${server.port}/?t=${server.token}" } }
}

/** First launch: nothing is set up yet, so the screen says how, with the phone page's QR. */
@Composable
fun WelcomeCard(api: SetupApi) {
    val url = rememberSetupUrl(api)
    Box(Modifier.fillMaxSize().padding(end = 64.dp, bottom = 56.dp), contentAlignment = Alignment.BottomEnd) {
        Row(
            Modifier.background(LocalAlmanakTheme.current.background, RoundedCornerShape(16.dp)).padding(28.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (url != null) QrCode(url, 200.dp)
            Column(Modifier.width(320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.welcome_title), style = Type.serifItalic(34.sp, Fg))
                Text(stringResource(if (url != null) R.string.welcome_scan else R.string.settings_no_network), style = Type.label(16.sp))
                Text(stringResource(R.string.welcome_ok), Modifier.padding(top = 8.dp), style = Type.label(13.sp, FgFaint))
            }
        }
    }
}
