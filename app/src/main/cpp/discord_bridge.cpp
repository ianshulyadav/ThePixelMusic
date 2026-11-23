// Thin JNI bridge between PixelMusic (Kotlin) and the Discord Social SDK (C++ only on Android).
// Every native call is made from ONE Kotlin thread (DiscordPresence's dispatcher), and SDK
// callbacks are delivered on that same thread by nativeRunCallbacks(), so no locking is needed.

#define DISCORDPP_IMPLEMENTATION
#include "discordpp.h"

#include <android/log.h>
#include <jni.h>

#include <memory>
#include <string>

#define LOG_TAG "PixelDiscord"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

JavaVM* gVm = nullptr;
jobject gSink = nullptr;  // global ref to the Kotlin DiscordNative.Sink
jmethodID gOnStatus = nullptr;
jmethodID gOnTokens = nullptr;
jmethodID gOnError = nullptr;
jmethodID gOnPresence = nullptr;

std::shared_ptr<discordpp::Client> gClient;
uint64_t gAppId = 0;

JNIEnv* currentEnv() {
    JNIEnv* env = nullptr;
    if (gVm == nullptr) return nullptr;
    if (gVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) return env;
    if (gVm->AttachCurrentThread(&env, nullptr) == JNI_OK) return env;
    return nullptr;
}

std::string toStd(JNIEnv* env, jstring s) {
    if (s == nullptr) return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return out;
}

void clearPendingException(JNIEnv* env) {
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}

void emitError(const std::string& where, const std::string& message) {
    JNIEnv* env = currentEnv();
    if (env == nullptr || gSink == nullptr) return;
    jstring jWhere = env->NewStringUTF(where.c_str());
    jstring jMsg = env->NewStringUTF(message.c_str());
    env->CallVoidMethod(gSink, gOnError, jWhere, jMsg);
    clearPendingException(env);
    env->DeleteLocalRef(jWhere);
    env->DeleteLocalRef(jMsg);
}

void emitTokens(const std::string& access, const std::string& refresh, int32_t expiresIn) {
    JNIEnv* env = currentEnv();
    if (env == nullptr || gSink == nullptr) return;
    jstring jAccess = env->NewStringUTF(access.c_str());
    jstring jRefresh = env->NewStringUTF(refresh.c_str());
    env->CallVoidMethod(gSink, gOnTokens, jAccess, jRefresh, static_cast<jint>(expiresIn));
    clearPendingException(env);
    env->DeleteLocalRef(jAccess);
    env->DeleteLocalRef(jRefresh);
}

void onTokenResult(discordpp::ClientResult result,
                   std::string accessToken,
                   std::string refreshToken,
                   discordpp::AuthorizationTokenType /*tokenType*/,
                   int32_t expiresIn,
                   std::string /*scopes*/) {
    if (!result.Successful()) {
        LOGW("Token exchange failed: %s", result.ToString().c_str());
        emitError("token", result.Error());
        return;
    }
    LOGI("Token exchange ok");
    emitTokens(accessToken, refreshToken, expiresIn);
}

// Discord rejects details/state shorter than 2 characters.
std::string atLeastTwo(std::string s) {
    while (s.size() < 2) s.push_back(' ');
    if (s.size() > 128) s.resize(128);
    return s;
}

}  // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    gVm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL
Java_com_unshoo_pixelmusic_data_discord_DiscordNative_nativeInit(JNIEnv* env, jobject /*thiz*/,
                                                                  jobject sink, jlong appId) {
    if (gClient) return;
    gAppId = static_cast<uint64_t>(appId);
    gSink = env->NewGlobalRef(sink);
    jclass cls = env->GetObjectClass(sink);
    gOnStatus = env->GetMethodID(cls, "onStatus", "(ILjava/lang/String;I)V");
    gOnTokens = env->GetMethodID(cls, "onTokens", "(Ljava/lang/String;Ljava/lang/String;I)V");
    gOnError = env->GetMethodID(cls, "onError", "(Ljava/lang/String;Ljava/lang/String;)V");
    gOnPresence = env->GetMethodID(cls, "onPresence", "(ZLjava/lang/String;)V");
    env->DeleteLocalRef(cls);

    gClient = std::make_shared<discordpp::Client>();
    gClient->SetApplicationId(gAppId);
    gClient->SetStatusChangedCallback([](discordpp::Client::Status status,
                                         discordpp::Client::Error error,
                                         int32_t errorDetail) {
        LOGI("status=%s error=%s detail=%d",
             discordpp::Client::StatusToString(status).c_str(),
             discordpp::Client::ErrorToString(error).c_str(), errorDetail);
        JNIEnv* cbEnv = currentEnv();
        if (cbEnv == nullptr || gSink == nullptr) return;
        jstring jErr = cbEnv->NewStringUTF(discordpp::Client::ErrorToString(error).c_str());
        cbEnv->CallVoidMethod(gSink, gOnStatus, static_cast<jint>(status), jErr,
                              static_cast<jint>(errorDetail));
        clearPendingException(cbEnv);
        cbEnv->DeleteLocalRef(jErr);
    });
    LOGI("Discord client created for app %llu", static_cast<unsigned long long>(gAppId));
}

extern "C" JNIEXPORT void JNICALL
Java_com_unshoo_pixelmusic_data_discord_DiscordNative_nativeRunCallbacks(JNIEnv*, jobject) {
    discordpp::RunCallbacks();
}

extern "C" JNIEXPORT void JNICALL
Java_com_unshoo_pixelmusic_data_discord_DiscordNative_nativeAuthorize(JNIEnv*, jobject) {
    if (!gClient) return;
    auto verifier = std::make_shared<discordpp::AuthorizationCodeVerifier>(
        gClient->CreateAuthorizationCodeVerifier());
    discordpp::AuthorizationArgs args{};
    args.SetClientId(gAppId);
    args.SetScopes(discordpp::Client::GetDefaultPresenceScopes());
    args.SetCodeChallenge(verifier->Challenge());
    gClient->Authorize(args, [verifier](discordpp::ClientResult result, std::string code,
                                        std::string redirectUri) {
        if (!result.Successful()) {
            LOGW("Authorize failed: %s", result.ToString().c_str());
            emitError("authorize", result.Error());
            return;
        }
        LOGI("Authorize ok, exchanging code");
        gClient->GetToken(gAppId, code, verifier->Verifier(), redirectUri, onTokenResult);
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_unshoo_pixelmusic_data_discord_DiscordNative_nativeRefreshToken(JNIEnv* env, jobject,
                                                                         jstring refreshToken) {
    if (!gClient) return;
    gClient->RefreshToken(gAppId, toStd(env, refreshToken), onTokenResult);
}

extern "C" JNIEXPORT void JNICALL
Java_com_unshoo_pixelmusic_data_discord_DiscordNative_nativeConnect(JNIEnv* env, jobject,
                                                                    jstring accessToken) {
    if (!gClient) return;
    gClient->UpdateToken(discordpp::AuthorizationTokenType::Bearer, toStd(env, accessToken),
                         [](discordpp::ClientResult result) {
                             if (!result.Successful()) {
                                 emitError("updateToken", result.Error());
                                 return;
                             }
                             gClient->Connect();
                         });
}

extern "C" JNIEXPORT void JNICALL
Java_com_unshoo_pixelmusic_data_discord_DiscordNative_nativeDisconnect(JNIEnv*, jobject) {
    if (gClient) gClient->Disconnect();
}

extern "C" JNIEXPORT void JNICALL
Java_com_unshoo_pixelmusic_data_discord_DiscordNative_nativeSetPresence(
    JNIEnv* env, jobject, jstring title, jstring stateText, jstring album, jstring largeImage,
    jstring smallImage, jlong startMs, jlong endMs, jstring partyId, jstring joinSecret,
    jstring buttonUrl, jint statusDisplayType) {
    if (!gClient) return;

    discordpp::Activity activity;
    activity.SetName("PixelMusic");
    activity.SetType(discordpp::ActivityTypes::Listening);
    activity.SetDetails(atLeastTwo(toStd(env, title)));
    // 0 = "Listening to PixelMusic", 2 = "Listening to <song>" in friends' member lists.
    activity.SetStatusDisplayType(static_cast<discordpp::StatusDisplayTypes>(statusDisplayType));
    std::string state = toStd(env, stateText);
    if (!state.empty()) activity.SetState(atLeastTwo(state));

    discordpp::ActivityAssets assets;
    std::string large = toStd(env, largeImage);
    std::string small = toStd(env, smallImage);
    std::string albumStr = toStd(env, album);
    if (!large.empty()) {
        assets.SetLargeImage(large);
        if (!albumStr.empty()) assets.SetLargeText(atLeastTwo(albumStr));
    }
    if (!small.empty()) {
        assets.SetSmallImage(small);
        assets.SetSmallText("PixelMusic");
    }
    activity.SetAssets(assets);

    if (startMs > 0) {
        discordpp::ActivityTimestamps timestamps;
        timestamps.SetStart(static_cast<uint64_t>(startMs));
        if (endMs > startMs) timestamps.SetEnd(static_cast<uint64_t>(endMs));
        activity.SetTimestamps(timestamps);
    }

    // Joinable "listen along" party (online songs only; empty strings disable it).
    // On mobile, accepting opens <portal deep-link URL>/_discord/join?secret=<joinSecret>,
    // which lands in PixelMusic (App Link) or on pixelmusic.me when the app isn't installed.
    std::string party = toStd(env, partyId);
    std::string secret = toStd(env, joinSecret);
    if (!party.empty() && !secret.empty()) {
        discordpp::ActivityParty p;
        p.SetId(party);
        p.SetCurrentSize(1);
        p.SetMaxSize(10);
        p.SetPrivacy(discordpp::ActivityPartyPrivacy::Public);
        activity.SetParty(p);
        discordpp::ActivitySecrets secrets;
        secrets.SetJoin(secret);
        activity.SetSecrets(secrets);
        activity.SetSupportedPlatforms(static_cast<discordpp::ActivityGamePlatforms>(
            static_cast<int>(discordpp::ActivityGamePlatforms::Android) |
            static_cast<int>(discordpp::ActivityGamePlatforms::IOS)));
    }
    std::string button = toStd(env, buttonUrl);
    if (!button.empty()) {
        discordpp::ActivityButton b;
        b.SetLabel("Listen on PixelMusic");
        b.SetUrl(button);
        activity.AddButton(b);
    }

    gClient->UpdateRichPresence(activity, [](discordpp::ClientResult result) {
        bool ok = result.Successful();
        std::string msg = ok ? std::string() : result.Error();
        if (ok) LOGI("UpdateRichPresence ok");
        else LOGW("UpdateRichPresence failed: %s", msg.c_str());
        JNIEnv* cbEnv = currentEnv();
        if (cbEnv == nullptr || gSink == nullptr) return;
        jstring jMsg = cbEnv->NewStringUTF(msg.c_str());
        cbEnv->CallVoidMethod(gSink, gOnPresence, static_cast<jboolean>(ok), jMsg);
        clearPendingException(cbEnv);
        cbEnv->DeleteLocalRef(jMsg);
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_unshoo_pixelmusic_data_discord_DiscordNative_nativeClearPresence(JNIEnv*, jobject) {
    if (gClient) gClient->ClearRichPresence();
}

// Full teardown: clears the status on Discord, drops the connection and destroys the client so
// nothing (token, session, callbacks) survives a Disconnect. A later Connect re-runs nativeInit.
extern "C" JNIEXPORT void JNICALL
Java_com_unshoo_pixelmusic_data_discord_DiscordNative_nativeShutdown(JNIEnv* env, jobject) {
    if (!gClient) return;
    gClient->ClearRichPresence();
    gClient->Disconnect();
    for (int i = 0; i < 5; ++i) discordpp::RunCallbacks();  // flush the clear/disconnect
    gClient.reset();
    discordpp::RunCallbacks();
    if (gSink != nullptr) {
        env->DeleteGlobalRef(gSink);
        gSink = nullptr;
    }
    LOGI("Discord client shut down");
}
