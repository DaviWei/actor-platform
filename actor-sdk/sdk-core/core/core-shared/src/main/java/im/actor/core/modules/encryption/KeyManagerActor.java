package im.actor.core.modules.encryption;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import im.actor.core.api.ApiEncryptionKey;
import im.actor.core.api.ApiEncryptionKeySignature;
import im.actor.core.api.rpc.RequestCreateNewKeyGroup;
import im.actor.core.api.rpc.RequestUploadEphermalKey;
import im.actor.core.api.rpc.ResponseCreateNewKeyGroup;
import im.actor.core.api.rpc.ResponseVoid;
import im.actor.core.modules.ModuleContext;
import im.actor.core.modules.encryption.entity.EncryptionKey;
import im.actor.core.modules.encryption.entity.EphemeralEncryptionKey;
import im.actor.core.modules.encryption.entity.PrivateKeyStorage;
import im.actor.core.util.ModuleActor;
import im.actor.core.util.RandomUtils;
import im.actor.core.network.RpcCallback;
import im.actor.core.network.RpcException;
import im.actor.runtime.Crypto;
import im.actor.runtime.Log;
import im.actor.runtime.Storage;
import im.actor.runtime.actors.Future;
import im.actor.runtime.actors.ask.AskRequest;
import im.actor.runtime.crypto.Curve25519;
import im.actor.runtime.storage.KeyValueRecord;
import im.actor.runtime.storage.KeyValueStorage;

public class KeyManagerActor extends ModuleActor {

    private static final String PRIVATE_KEYS = "private_keys";

    private static final String TAG = "KeyManagerActor";

    private PrivateKeyStorage privateKeyStorage;
    private KeyValueStorage ephemeralStorage;
    private boolean isReady = false;

    public KeyManagerActor(ModuleContext context) {
        super(context);
    }

    @Override
    public void preStart() {
        ephemeralStorage = Storage.createKeyValue("ephemeral_keys");

        byte[] data = preferences().getBytes(PRIVATE_KEYS);
        if (data != null) {
            try {
                privateKeyStorage = new PrivateKeyStorage(data);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        if (privateKeyStorage == null) {
            Log.d(TAG, "Generating new encryption keys...");

            EncryptionKey identityKey = new EncryptionKey(RandomUtils.nextRid(),
                    Curve25519.keyGen(Crypto.randomBytes(64)));
            ArrayList<EncryptionKey> keyPairs = new ArrayList<EncryptionKey>();
            keyPairs.add(new EncryptionKey(RandomUtils.nextRid(),
                    Curve25519.keyGen(Crypto.randomBytes(64))));

            privateKeyStorage = new PrivateKeyStorage(identityKey, keyPairs, 0);
            preferences().putBytes(PRIVATE_KEYS, privateKeyStorage.toByteArray());
        }

        if (privateKeyStorage.getKeyGroupId() == 0) {
            Log.d(TAG, "Uploading main encryption keys...");

            EncryptionKey identityKey = privateKeyStorage.getIdentityKey();
            ApiEncryptionKey apiEncryptionKey =
                    new ApiEncryptionKey(
                            identityKey.getKeyId(),
                            identityKey.getKeyAlg(),
                            identityKey.getPublicKey(),
                            null);

            ArrayList<String> encryption = new ArrayList<String>();
            encryption.add("curve25519");
            encryption.add("Ed25519");
            encryption.add("kuznechik128");
            encryption.add("streebog256");
            encryption.add("sha256");
            encryption.add("sha512");
            encryption.add("aes128");

            ArrayList<ApiEncryptionKey> keys = new ArrayList<ApiEncryptionKey>();
            ArrayList<ApiEncryptionKeySignature> keySignatures = new ArrayList<ApiEncryptionKeySignature>();
            for (EncryptionKey key : privateKeyStorage.getKeys()) {
                ApiEncryptionKey apiKey =
                        new ApiEncryptionKey(
                                key.getKeyId(),
                                key.getKeyAlg(),
                                key.getPublicKey(),
                                null);
                keys.add(apiKey);


                byte[] signature = Curve25519.calculateSignature(Crypto.randomBytes(64), identityKey.getPrivateKey(), apiKey.toByteArray());
                keySignatures.add(
                        new ApiEncryptionKeySignature(
                                key.getKeyId(),
                                "Ed25519",
                                signature));
            }

            request(new RequestCreateNewKeyGroup(apiEncryptionKey, encryption, keys, keySignatures), new RpcCallback<ResponseCreateNewKeyGroup>() {
                @Override
                public void onResult(ResponseCreateNewKeyGroup response) {
                    privateKeyStorage = privateKeyStorage.markUploaded(response.getKeyGroupId());
                    preferences().putBytes(PRIVATE_KEYS, privateKeyStorage.toByteArray());
                    onMainKeysReady();
                }

                @Override
                public void onError(RpcException e) {
                    Log.w(TAG, "Keys upload error");
                    Log.e(TAG, e);

                    // Just ignore
                }
            });
        } else {
            onMainKeysReady();
        }
    }

    private void onMainKeysReady() {
        Log.d(TAG, "Main Keys are ready");

        // Generating ephemeral keys
        List<KeyValueRecord> records = ephemeralStorage.loadAllItems();
        for (int i = 0; i < 100 - records.size(); i++) {
            long randomId = RandomUtils.nextRid();
            EncryptionKey encryptionKey = new EncryptionKey(
                    randomId,
                    Curve25519.keyGen(Crypto.randomBytes(64)));
            EphemeralEncryptionKey ephemeralEncryptionKey =
                    new EphemeralEncryptionKey(encryptionKey, false);
            ephemeralStorage.addOrUpdateItem(randomId, ephemeralEncryptionKey.toByteArray());
        }

        // Uploading ephemeral keys
        records = ephemeralStorage.loadAllItems();

        final ArrayList<EphemeralEncryptionKey> pendingEphermalKeys = new ArrayList<EphemeralEncryptionKey>();
        for (KeyValueRecord record : records) {
            try {
                EphemeralEncryptionKey encryptionKey = new EphemeralEncryptionKey(record.getData());
                if (!encryptionKey.isUploaded()) {
                    pendingEphermalKeys.add(encryptionKey);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        if (pendingEphermalKeys.size() > 0) {
            ArrayList<ApiEncryptionKey> uploadingKeys = new ArrayList<ApiEncryptionKey>();
            ArrayList<ApiEncryptionKeySignature> uploadingSignatures = new ArrayList<ApiEncryptionKeySignature>();
            for (EphemeralEncryptionKey k : pendingEphermalKeys) {
                ApiEncryptionKey apiKey =
                        new ApiEncryptionKey(
                                k.getEncryptionKey().getKeyId(),
                                k.getEncryptionKey().getKeyAlg(),
                                k.getEncryptionKey().getPublicKey(),
                                null);
                uploadingKeys.add(apiKey);


                byte[] signature = Curve25519.calculateSignature(Crypto.randomBytes(64),
                        privateKeyStorage.getIdentityKey().getPrivateKey(), apiKey.toByteArray());
                uploadingSignatures.add(
                        new ApiEncryptionKeySignature(
                                k.getEncryptionKey().getKeyId(),
                                "Ed25519",
                                signature));
            }

            request(new RequestUploadEphermalKey(privateKeyStorage.getKeyGroupId(), uploadingKeys, uploadingSignatures), new RpcCallback<ResponseVoid>() {
                @Override
                public void onResult(ResponseVoid response) {
                    List<KeyValueRecord> updated = new ArrayList<KeyValueRecord>();
                    for (EphemeralEncryptionKey k : pendingEphermalKeys) {
                        updated.add(new KeyValueRecord(k.getEncryptionKey().getKeyId(),
                                k.markUploaded().toByteArray()));
                    }
                    ephemeralStorage.addOrUpdateItems(updated);


                    onAllKeysReady();
                }

                @Override
                public void onError(RpcException e) {
                    Log.w(TAG, "Ephemeral keys upload error");
                    Log.e(TAG, e);

                    // Ignore
                }
            });
        } else {
            onAllKeysReady();
        }
    }

    private void onAllKeysReady() {
        Log.d(TAG, "Ephemeral Keys are ready");

        // Now we can start receiving encrypted messages

        isReady = true;
        unstashAll();
    }

    private void fetchOwnKey(Future future) {
        List<KeyValueRecord> records = ephemeralStorage.loadAllItems();
        EphemeralEncryptionKey ephemeralEncryptionKey;
        try {
            ephemeralEncryptionKey = new EphemeralEncryptionKey(records.get(RandomUtils.randomId(records.size())).getData());
        } catch (IOException e) {
            e.printStackTrace();
            future.onError(e);
            return;
        }

        future.onResult(new FetchOwnKeyResult(privateKeyStorage.getIdentityKey(), ephemeralEncryptionKey.getEncryptionKey()));
    }

    private void fetchKeyGroup(Future future) {
        future.onResult(new FetchOwnKeyGroupResult(privateKeyStorage.getKeyGroupId()));
    }

    @Override
    public void onReceive(Object message) {
        if (message instanceof AskRequest && !isReady) {
            stash();
            return;
        }
        super.onReceive(message);
    }

    @Override
    public boolean onAsk(Object message, Future future) {
        if (message instanceof FetchOwnKey) {
            fetchOwnKey(future);
            return false;
        } else if (message instanceof FetchOwnKeyGroup) {
            fetchKeyGroup(future);
            return false;
        }
        return super.onAsk(message, future);
    }

    public static class FetchOwnKey {

    }

    public static class FetchOwnKeyResult {

        private EncryptionKey identityKey;
        private EncryptionKey ephemeralKey;

        public FetchOwnKeyResult(EncryptionKey identityKey, EncryptionKey ephemeralKey) {
            this.identityKey = identityKey;
            this.ephemeralKey = ephemeralKey;
        }

        public EncryptionKey getIdentityKey() {
            return identityKey;
        }

        public EncryptionKey getEphemeralKey() {
            return ephemeralKey;
        }
    }

    public static class FetchOwnKeyGroup {

    }

    public static class FetchOwnKeyGroupResult {
        private int keyGroupId;

        public FetchOwnKeyGroupResult(int keyGroupId) {
            this.keyGroupId = keyGroupId;
        }

        public int getKeyGroupId() {
            return keyGroupId;
        }
    }
}