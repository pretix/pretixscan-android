package eu.pretix.pretixscan.utils


import android.annotation.SuppressLint
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import android.util.Base64
import android.util.Log

import java.io.IOException
import java.nio.charset.Charset
import java.security.InvalidAlgorithmParameterException
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.NoSuchAlgorithmException
import java.security.NoSuchProviderException
import java.security.UnrecoverableKeyException
import java.security.cert.CertificateException

import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.KeyGenerator
import javax.crypto.NoSuchPaddingException
import javax.crypto.SecretKey

object KeystoreHelper {

    private val SECURE_KEY_NAME = "prefs_encryption_key"
    private val ANDROID_KEY_STORE = "AndroidKeyStore"

    // Works since android M, for previous versions this will simply return the plain value unaltered
    fun secureValue(value: String, encrypt: Boolean): String {
        if (value.length > 0 && android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE)
                keyStore.load(null)
                var key: SecretKey? = keyStore.getKey(SECURE_KEY_NAME, null) as SecretKey?

                if (key == null) {
                    val keyGenerator = KeyGenerator.getInstance(
                            KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
                    keyGenerator.init(KeyGenParameterSpec.Builder(SECURE_KEY_NAME,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                            .setBlockModes(KeyProperties.BLOCK_MODE_ECB)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
                            .setRandomizedEncryptionRequired(false)
                            .build())
                    keyGenerator.generateKey()
                    key = keyStore.getKey(SECURE_KEY_NAME, null) as SecretKey
                }

                /*
                                *  It's quite ok to use ECB here, because:
                                *   - the "plaintext" will most likely fit into one or two encryption blocks
                                *   - the "plaintext" only contains pseudo-random printable characters
                                *   - we don't want to store an additional IV
                                */
                @SuppressLint("GetInstance") val cipher = Cipher.getInstance(
                        KeyProperties.KEY_ALGORITHM_AES + "/" + KeyProperties.BLOCK_MODE_ECB + "/"
                                + KeyProperties.ENCRYPTION_PADDING_PKCS7)

                if (encrypt) {
                    cipher.init(Cipher.ENCRYPT_MODE, key)
                    val bytes = cipher.doFinal(value!!.toByteArray())
                    return Base64.encodeToString(bytes, Base64.NO_WRAP)
                } else {
                    cipher.init(Cipher.DECRYPT_MODE, key)
                    val bytes = Base64.decode(value, Base64.NO_WRAP)
                    return String(cipher.doFinal(bytes), Charset.forName("UTF-8"))
                }

            } catch (@SuppressLint("NewApi") e: UserNotAuthenticatedException) {
                Log.d("KeystoreHelper", "UserNotAuthenticatedException: " + e.message)
                return value
            } catch (@SuppressLint("NewApi") e: KeyPermanentlyInvalidatedException) {
                Log.d("KeystoreHelper", "KeyPermanentlyInvalidatedException: " + e.message)
                return value
            } catch (e: BadPaddingException) {
                throw RuntimeException(e)
            } catch (e: IllegalBlockSizeException) {
                throw RuntimeException(e)
            } catch (e: KeyStoreException) {
                throw RuntimeException(e)
            } catch (e: CertificateException) {
                throw RuntimeException(e)
            } catch (e: UnrecoverableKeyException) {
                throw RuntimeException(e)
            } catch (e: NoSuchPaddingException) {
                throw RuntimeException(e)
            } catch (e: NoSuchProviderException) {
                throw RuntimeException(e)
            } catch (e: IOException) {
                throw RuntimeException(e)
            } catch (e: InvalidAlgorithmParameterException) {
                throw RuntimeException(e)
            } catch (e: NoSuchAlgorithmException) {
                throw RuntimeException(e)
            } catch (e: InvalidKeyException) {
                throw RuntimeException(e)
            }

        }
        return value
    }
}