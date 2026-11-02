package prefix924.shhhhhh

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import java.util.Base64
import prefix924.shhhhhh.crypto.CryptoKeyPair

internal val CryptoKeyPairSaver: Saver<CryptoKeyPair?, Any> =
  listSaver(
    save = { keyPair ->
      if (keyPair != null) {
        listOf(
          Base64.getUrlEncoder().withoutPadding().encodeToString(keyPair.privateKey),
          Base64.getUrlEncoder().withoutPadding().encodeToString(keyPair.publicKey),
        )
      } else {
        emptyList()
      }
    },
    restore = { list ->
      if (list.size >= 2) {
        runCatching {
          CryptoKeyPair(
            privateKey = Base64.getUrlDecoder().decode(list[0]),
            publicKey = Base64.getUrlDecoder().decode(list[1]),
          )
        }.getOrNull()
      } else {
        null
      }
    },
  )
