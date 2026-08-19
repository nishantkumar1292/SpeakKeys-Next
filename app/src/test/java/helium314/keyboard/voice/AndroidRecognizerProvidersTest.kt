package helium314.keyboard.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.elishaazaria.sayboard.data.InstalledModelReference
import com.elishaazaria.sayboard.data.ModelType
import com.elishaazaria.sayboard.recognition.auth.AuthTokenProvider
import com.elishaazaria.sayboard.recognition.preferences.PreferencesRepository
import helium314.keyboard.voice.streaming.TaraRealtimeRecognizerSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidRecognizerProvidersTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = Mockito.mock(PreferencesRepository::class.java).also {
        Mockito.`when`(it.getVoiceOutputStyle()).thenReturn("mixed")
        Mockito.`when`(it.getModelsOrder()).thenReturn(emptyList())
        Mockito.`when`(it.getSarvamApiKey()).thenReturn("")
        Mockito.`when`(it.getElevenLabsApiKey()).thenReturn("")
        Mockito.`when`(it.getOpenaiApiKey()).thenReturn("")
    }

    @Test
    fun configuredTaraAppearsOnceAndUsesADowngradeSafePersistedFamily() {
        val providers = providers(signedIn = true, endpoint = "wss://voice.example.com/v1/realtime")

        val tara = providers.installedModels().filter {
            it.path == AndroidRecognizerProviders.TARA_REALTIME_PATH
        }

        assertEquals(1, tara.size)
        assertEquals("Natural Hinglish", tara.single().name)
        assertEquals(ModelType.ProxiedWhisperCloud, tara.single().type)
        assertTrue(providers.recognizerSourceForModel(tara.single()) is TaraRealtimeRecognizerSource)
    }

    @Test
    fun taraIsAbsentWithoutADeployedEndpoint() {
        val providers = providers(signedIn = true, endpoint = "")

        assertFalse(
            providers.installedModels().any {
                it.path == AndroidRecognizerProviders.TARA_REALTIME_PATH
            },
        )
        assertNull(providers.recognizerSourceForModel(taraReference()))
    }

    @Test
    fun taraIsAbsentWhenSignedOut() {
        val providers = providers(signedIn = false, endpoint = "wss://voice.example.com/v1/realtime")

        assertFalse(
            providers.installedModels().any {
                it.path == AndroidRecognizerProviders.TARA_REALTIME_PATH
            },
        )
        assertNull(providers.recognizerSourceForModel(taraReference()))
    }

    private fun providers(signedIn: Boolean, endpoint: String): AndroidRecognizerProviders {
        val auth = Mockito.mock(AuthTokenProvider::class.java)
        Mockito.`when`(auth.isSignedIn).thenReturn(signedIn)
        return AndroidRecognizerProviders(
            context = context,
            prefs = prefs,
            authTokenProvider = auth,
            taraRealtimeEndpoint = endpoint,
        )
    }

    private fun taraReference() = InstalledModelReference(
        path = AndroidRecognizerProviders.TARA_REALTIME_PATH,
        name = "Natural Hinglish",
        type = ModelType.ProxiedWhisperCloud,
    )
}
