package com.simonproyt.legacysignal.crypto

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyStore
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SessionStore
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.KyberPreKeyStore
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.ecc.ECPublicKey
import java.util.UUID

class SharedPrefsSignalProtocolStore(private val context: Context) : SignalProtocolStore {
    private val prefs: SharedPreferences = context.getSharedPreferences("signal_protocol_store", Context.MODE_PRIVATE)

    override fun getIdentityKeyPair(): IdentityKeyPair {
        val bytes = Base64.decode(prefs.getString("identity_key_pair", ""), Base64.NO_WRAP)
        return IdentityKeyPair(bytes)
    }

    fun saveIdentityKeyPair(identityKeyPair: IdentityKeyPair) {
        prefs.edit().putString("identity_key_pair", Base64.encodeToString(identityKeyPair.serialize(), Base64.NO_WRAP)).apply()
    }

    override fun getLocalRegistrationId(): Int {
        return prefs.getInt("local_registration_id", 0)
    }

    fun saveLocalRegistrationId(registrationId: Int) {
        prefs.edit().putInt("local_registration_id", registrationId).apply()
    }

    fun saveDeliveryCertificate(certBytes: ByteArray) {
        prefs.edit().putString("delivery_certificate", Base64.encodeToString(certBytes, Base64.NO_WRAP)).apply()
    }

    fun getDeliveryCertificate(): ByteArray? {
        val b64 = prefs.getString("delivery_certificate", null) ?: return null
        return Base64.decode(b64, Base64.NO_WRAP)
    }

    override fun saveIdentity(address: SignalProtocolAddress?, identityKey: IdentityKey?): org.signal.libsignal.protocol.state.IdentityKeyStore.IdentityChange? {
        if (address == null || identityKey == null) return org.signal.libsignal.protocol.state.IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        prefs.edit().putString("identity_${address.name}", Base64.encodeToString(identityKey.serialize(), Base64.NO_WRAP)).apply()
        return org.signal.libsignal.protocol.state.IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED // Replace with proper change logic if necessary
    }

    override fun isTrustedIdentity(
        address: SignalProtocolAddress?,
        identityKey: IdentityKey?,
        direction: IdentityKeyStore.Direction?
    ): Boolean {
        return true
    }

    override fun getIdentity(address: SignalProtocolAddress?): IdentityKey? {
        if (address == null) return null
        val b64 = prefs.getString("identity_${address.name}", null) ?: return null
        return IdentityKey(Base64.decode(b64, Base64.NO_WRAP), 0)
    }

    override fun loadPreKey(preKeyId: Int): PreKeyRecord {
        val b64 = prefs.getString("prekey_$preKeyId", null) ?: throw org.signal.libsignal.protocol.InvalidKeyIdException("No prekey")
        return PreKeyRecord(Base64.decode(b64, Base64.NO_WRAP))
    }

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord?) {
        if (record == null) return
        prefs.edit().putString("prekey_$preKeyId", Base64.encodeToString(record.serialize(), Base64.NO_WRAP)).apply()
    }

    override fun containsPreKey(preKeyId: Int): Boolean {
        return prefs.contains("prekey_$preKeyId")
    }

    override fun removePreKey(preKeyId: Int) {
        prefs.edit().remove("prekey_$preKeyId").apply()
    }

    override fun loadSession(address: SignalProtocolAddress?): SessionRecord {
        if (address == null) return SessionRecord()
        val b64 = prefs.getString("session_${address.name}_${address.deviceId}", null)
        return if (b64 != null) SessionRecord(Base64.decode(b64, Base64.NO_WRAP)) else SessionRecord()
    }

    override fun loadExistingSessions(addresses: MutableList<SignalProtocolAddress>?): MutableList<SessionRecord> {
        val sessions = mutableListOf<SessionRecord>()
        addresses?.forEach { sessions.add(loadSession(it)) }
        return sessions
    }

    override fun getSubDeviceSessions(name: String?): MutableList<Int> {
        if (name == null) return mutableListOf()
        val prefix = "session_${name}_"
        val devices = mutableListOf<Int>()
        for (key in prefs.all.keys) {
            if (key.startsWith(prefix)) {
                try {
                    val deviceIdStr = key.substring(prefix.length)
                    devices.add(deviceIdStr.toInt())
                } catch (e: NumberFormatException) {
                    // Ignore
                }
            }
        }
        return devices
    }

    override fun storeSession(address: SignalProtocolAddress?, record: SessionRecord?) {
        if (address == null || record == null) return
        prefs.edit().putString("session_${address.name}_${address.deviceId}", Base64.encodeToString(record.serialize(), Base64.NO_WRAP)).apply()
    }

    override fun containsSession(address: SignalProtocolAddress?): Boolean {
        if (address == null) return false
        return prefs.contains("session_${address.name}_${address.deviceId}")
    }

    override fun deleteSession(address: SignalProtocolAddress?) {
        if (address == null) return
        prefs.edit().remove("session_${address.name}_${address.deviceId}").apply()
    }

    override fun deleteAllSessions(name: String?) {
    }

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord {
        val b64 = prefs.getString("signed_prekey_$signedPreKeyId", null) ?: throw org.signal.libsignal.protocol.InvalidKeyIdException("No signed prekey")
        return SignedPreKeyRecord(Base64.decode(b64, Base64.NO_WRAP))
    }

    override fun loadSignedPreKeys(): MutableList<SignedPreKeyRecord> {
        return mutableListOf()
    }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord?) {
        if (record == null) return
        prefs.edit().putString("signed_prekey_$signedPreKeyId", Base64.encodeToString(record.serialize(), Base64.NO_WRAP)).apply()
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean {
        return prefs.contains("signed_prekey_$signedPreKeyId")
    }

    override fun removeSignedPreKey(signedPreKeyId: Int) {
        prefs.edit().remove("signed_prekey_$signedPreKeyId").apply()
    }

    override fun loadKyberPreKey(preKeyId: Int): KyberPreKeyRecord {
        val b64 = prefs.getString("kyber_prekey_$preKeyId", null) ?: throw org.signal.libsignal.protocol.InvalidKeyIdException("No kyber prekey")
        return KyberPreKeyRecord(Base64.decode(b64, Base64.NO_WRAP))
    }

    override fun loadKyberPreKeys(): MutableList<KyberPreKeyRecord> {
        return mutableListOf()
    }

    override fun storeKyberPreKey(preKeyId: Int, record: KyberPreKeyRecord?) {
        if (record == null) return
        prefs.edit().putString("kyber_prekey_$preKeyId", Base64.encodeToString(record.serialize(), Base64.NO_WRAP)).apply()
    }

    override fun containsKyberPreKey(preKeyId: Int): Boolean {
        return prefs.contains("kyber_prekey_$preKeyId")
    }

    override fun markKyberPreKeyUsed(preKeyId: Int, timestamp: Int, pubKey: ECPublicKey?) {
        // Not necessary for a simple store right now
    }

    override fun storeSenderKey(address: SignalProtocolAddress?, uuid: UUID?, record: SenderKeyRecord?) {}

    override fun loadSenderKey(address: SignalProtocolAddress?, uuid: UUID?): SenderKeyRecord {
        throw UnsupportedOperationException("SenderKeyStore not implemented")
    }
}
