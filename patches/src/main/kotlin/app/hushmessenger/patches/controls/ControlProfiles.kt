package app.hushmessenger.patches.controls

/**
 * What differs between the Redex builds of Messenger the controls support. It's the same app
 * under different obfuscated names and a few shifted instruction positions, so each build keeps its
 * own exact hook list and the references the validators pin. Nothing is matched by count alone.
 *
 * scripts/profiles records each build, and `CompatReport.java --kotlin` turns a record into a new
 * profile. CompatProfileTest fails when a record and its profile here disagree.
 */
internal class ControlProfile(
    val hooks: Map<String, Set<String>>,
    /** Plugin gates compare their cached answer with this "not computed yet" sentinel. */
    val pluginSentinel: String,
    val preferenceGetter: String,
    /** The Notifications tab's "hide suggestions" preference key and its server-override check. */
    val peopleKey: String,
    val peopleFlagCheck: String,
    val subtabsSupplier: String,
    /** The external-browser preference key and the index of the read that loads it. */
    val browserPreferenceKey: String,
    val browserPreferenceIndex: Int,
    val bubbleCapabilityGetter: String,
    val bubbleRolloutGetter: String,
    /** Existing attachment, long-lived shortcut and conversation routes, separated by |. */
    val nativeBubbleRoutes: String,
    /** Main section closure, scope and native joined predicate, connected before any mutation. */
    val nativeCommunityInbox: String,
)

/** 584.0.0.49.98's one arm64 build so far. Generated from 346814894's record. */
internal val PROFILE_346814894 = ControlProfile(
    hooks = mapOf(
        "ad_context_banner" to setOf("LX/Lfk;->A00()Z"),
        "ad_events" to setOf(
            "LX/28I;->DpR(LX/03C;Z)V",
            "LX/5C3;->A00(LX/58c;Lcom/facebook/auth/usersession/FbUserSession;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
        ),
        "ai_commands" to setOf("LX/6O7;->A06(LX/6O7;Lcom/facebook/messaging/model/threadkey/ThreadKey;)Ljava/util/List;"),
        "ai_fab" to setOf("LX/6l1;->render(LX/2DF;)LX/1J7;"),
        "ai_mention_rows" to setOf(
            "LX/1v5;->A0Q()Z", "LX/1v5;->A0d(Lcom/facebook/auth/usersession/FbUserSession;)Z",
            "LX/1v5;->A0t(Lcom/facebook/auth/usersession/FbUserSession;Lcom/facebook/messaging/model/threadkey/ThreadKey;Lcom/facebook/xapp/messaging/capability/vector/Capabilities;)Z",
            "LX/1v5;->A0v(Lcom/facebook/auth/usersession/FbUserSession;Lcom/facebook/messaging/model/threadkey/ThreadKey;Lcom/facebook/xapp/messaging/capability/vector/Capabilities;ZZ)Z",
            "LX/1v5;->A0y(Lcom/facebook/auth/usersession/FbUserSession;Lcom/facebook/xapp/messaging/capability/vector/Capabilities;)Z",
        ),
        "ai_mentions" to setOf(
            "LX/6OI;->A7A(LX/M1O;Lcom/google/common/collect/ImmutableList\$Builder;)V",
            "LX/6OK;->A7A(LX/M1O;Lcom/google/common/collect/ImmutableList\$Builder;)V",
            "LX/6OL;->A7A(LX/M1O;Lcom/google/common/collect/ImmutableList\$Builder;)V",
        ),
        "ai_menu" to setOf("LX/GGI;->A00()Z", "LX/GGI;->A01()Z", "LX/I5w;->A00()Z", "LX/I5w;->A01()Z"),
        "ai_muse_tab" to setOf("LX/1n0;->A02(LX/1n0;)Z"),
        "ai_search" to setOf("LX/9ig;->A0A(LX/9ig;)Z", "LX/9ig;->A0B(LX/9ig;)Z"),
        "ai_search_chip" to setOf("LX/CDF;->render(LX/2DF;)LX/1J7;"),
        "ai_search_hint" to setOf("LX/28z;->A00(LX/1S0;LX/1yn;Lcom/facebook/mig/scheme/interfaces/MigColorScheme;Z)LX/1J7;"),
        "ai_search_screen_hint" to setOf("LX/HqR;->A00(Lcom/facebook/auth/usersession/FbUserSession;LX/HqR;)V"),
        "ai_sticker_cell" to setOf("LX/CVr;->render(LX/2DF;)LX/1J7;"),
        "ai_sticker_grid" to setOf("LX/CZ2;->render(LX/2DF;)LX/1J7;"),
        "ai_stickers" to setOf("LX/NPE;->A03(LX/NPE;)Z", "LX/NPj;->A07(LX/NPj;)Z"),
        "ai_tab" to setOf("LX/1n0;->A03(LX/1n0;)Z"),
        "ai_toolbar" to setOf("LX/2Qv;->A04()Z"),
        "allow_screenshot" to setOf(
            "LX/8wp;->A00(Landroid/view/Window;)V", "LX/8wq;->onScreenCaptured()V", "LX/Znv;->run()V",
            "Lcom/facebook/screenshot/ScreenshotContentObserver;->onChange(ZLandroid/net/Uri;)V",
        ),
        "analytics_uploads" to setOf(
            "LX/0aV;->onStartCommand(Landroid/content/Intent;II)I",
            "LX/0aV;->onStartJob(Landroid/app/job/JobParameters;)Z", "LX/4ek;->A05(LX/5dJ;Ljava/lang/String;)V",
            "LX/Zos;->run()V", "LX/a1d;->run()V",
            "Lcom/facebook/analytics2/logger/GooglePlayUploadService;->onStartCommand(Landroid/content/Intent;II)I",
            "Lcom/facebook/analytics2/logger/legacy/uploader/AlarmBasedUploadService;->onStartCommand(Landroid/content/Intent;II)I",
            "Lcom/facebook/analytics2/logger/legacy/uploader/HighPriUploadRetryReceiver;->onReceive(Landroid/content/Context;Landroid/content/Intent;)V",
            "Lcom/facebook/analytics2/logger/legacy/uploader/LollipopUploadService;->onStartCommand(Landroid/content/Intent;II)I",
            "Lcom/facebook/analytics2/logger/legacy/uploader/LollipopUploadService;->onStartJob(Landroid/app/job/JobParameters;)Z",
            "Lcom/facebook/analytics2/logger/service/LollipopUploadSafeService;->onStartCommand(Landroid/content/Intent;II)I",
            "Lcom/facebook/analytics2/logger/service/LollipopUploadSafeService;->onStartJob(Landroid/app/job/JobParameters;)Z",
        ),
        "anonymous_stories" to setOf(
            "LX/ICH;->C7h(Lcom/facebook/messaging/montage/model/MontageCard;Z)V",
            "Lcom/facebook/messaging/montage/model/MontageBucketPreview;-><init>(Lcom/facebook/messaging/montage/model/MontageBucketKey;Lcom/facebook/messaging/montage/model/MontageBucketLooperLoggingItem;Lcom/facebook/messaging/montage/model/MontageCard;Lcom/facebook/user/model/UserKey;Lcom/google/common/collect/ImmutableList;Ljava/lang/Integer;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IIZZZZZ)V",
        ),
        "app_icons" to setOf(
            "LX/6Tj;->A02(Lcom/facebook/auth/usersession/FbUserSession;)Z",
            "LX/6Tj;->A03(Lcom/facebook/auth/usersession/FbUserSession;)Z",
        ),
        "attribution_uploads" to setOf("Lcom/facebook/attribution/LatStatusJob;->A00(Lcom/facebook/attribution/LatStatusJob;Lcom/facebook/auth/usersession/FbUserSession;)V"),
        "auto_reply" to setOf("Lcom/facebook/messaging/notify/type/NewMessageNotification;-><init>(Lcom/facebook/messaging/accountswitch/model/MessengerAccountType;Lcom/facebook/messaging/model/messages/Message;Lcom/facebook/messaging/model/threads/ThreadSummary;LX/8LR;LX/8aH;Lcom/facebook/messaging/push/flags/ServerMessageAlertFlags;Lcom/facebook/push/constants/PushProperty;Ljava/lang/Long;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/util/Map;ZZZZZZZ)V"),
        "avatar_stickers" to setOf("LX/NPE;->A01(LX/NPE;)Z"),
        "avatar_tabs" to setOf("Lcom/facebook/messaging/msys/thread/composer/configuration/xapp/BaseXappComposerConfigurationFactory;->A6e(LX/AZV;)V"),
        "browser" to setOf("Lcom/facebook/messaging/browser/util/MessengerBrowserLauncher;->A0K(Landroid/net/Uri;Lcom/facebook/auth/usersession/FbUserSession;)Z"),
        "bubble_mode" to setOf("LX/2Nk;->A01(Lcom/facebook/auth/usersession/FbUserSession;)Z"),
        "bubbles" to setOf("LX/2Nk;->A00()Z"),
        "business_suggestions" to setOf("LX/6N5;->A05(LX/6N5;)Z", "LX/6O4;->A04(LX/6O4;)Z", "LX/Lfh;->A02()Z"),
        "chat_animation" to setOf("Landroidx/fragment/app/Fragment;->onCreateAnimation(IZI)Landroid/view/animation/Animation;"),
        "chat_fragment" to setOf("LX/1mr;-><init>()V"),
        "chat_inbox" to setOf("LX/1kx;-><init>()V"),
        "chat_legacy" to setOf("LX/1mp;->onCreateAnimation(IZI)Landroid/view/animation/Animation;"),
        "chat_promotions" to setOf("LX/Lfj;->A0E()Z", "LX/Lfj;->A0F()Z"),
        "community_inbox" to setOf("LX/294;->invoke(Ljava/lang/Object;)Ljava/lang/Object;"),
        "delta_unsent" to setOf("LX/UOr;->BzN(I)Z"),
        "disappearing_swipe" to setOf("Lcom/facebook/messaging/threadview/overscroll/ui/OverScrollActionBehavior;->onStartNestedScroll(Landroidx/coordinatorlayout/widget/CoordinatorLayout;Landroid/view/View;Landroid/view/View;Landroid/view/View;II)Z"),
        "emoji_drawer" to setOf(
            "LX/96u;->A01(Lcom/facebook/xapp/messaging/capability/vector/Capabilities;LX/AZV;LX/DhV;LX/Bhr;LX/96u;LX/943;Z)V",
            "LX/Ark;->render(LX/2DF;)LX/1J7;", "LX/As1;->A04(F)LX/CWT;",
            "LX/Dba;->invoke(Ljava/lang/Object;)Ljava/lang/Object;",
        ),
        "emoji_search" to setOf("LX/6O0;->A8q(Landroid/text/Editable;Z)V"),
        "emoji_typeface" to setOf("LX/1Nk;->A00()Landroid/graphics/Typeface;"),
        "event_prompts" to setOf("LX/Lfj;->A08()Z", "LX/Lfj;->A09()Z"),
        "external_gallery" to setOf(
            "LX/6MA;->onActivityResult(IILandroid/content/Intent;)V",
            "LX/9DN;->BkO(Lcom/facebook/xapp/messaging/capability/vector/Capabilities;LX/AZV;LX/6NI;Lcom/facebook/xapp/messaging/composer/event/ComposerEvent;)V",
            "LX/AaX;->C0w(Landroid/os/Bundle;LX/6NI;)Z", "LX/AwQ;->Bpf(LX/4Jq;Ljava/lang/String;)V",
            "LX/B5V;->A00(Landroidx/fragment/app/Fragment;Lcom/facebook/auth/usersession/FbUserSession;Lcom/facebook/messaging/model/threadkey/ThreadKey;Lcom/facebook/xapp/messaging/composer/activity/event/ActivityResultEvent;LX/6NI;)V",
        ),
        "facebook" to setOf(
            "LX/2Qv;->A0C()Z", "LX/3qo;->A00()Z", "LX/3qp;->A00()Z", "LX/GGK;->A02()Z", "LX/GGK;->A05()Z",
            "LX/GM9;->A02()Z", "LX/I5A;->A04()Z", "LX/I5R;->A06()Z", "LX/I5z;->A06()Z",
        ),
        "facebook_badge" to setOf("Lcom/facebook/messaging/inbox/tab/plugins/core/tabtoolbarbutton/facebookbutton/facebooktoolbarbutton/FacebookButtonTabButtonImplementation;->A02()LX/2RS;"),
        "facebook_profile" to setOf(
            "LX/I6L;->A00()Z", "LX/I6T;->A02()Z", "LX/I6U;->A01()Z", "LX/I6W;->A02()Z", "LX/I6Y;->A02()Z",
            "LX/I6d;->A03()Z", "LX/I6e;->A03()Z", "LX/I6g;->A01()Z", "LX/I6h;->A01()Z", "LX/I6j;->A06()Z",
            "LX/I6k;->A06()Z", "LX/I6l;->A06()Z", "LX/I6m;->A06()Z",
        ),
        "font_by_name" to setOf("LX/Iru;->A00(Landroid/content/Context;Ljava/lang/String;I)Landroid/graphics/Typeface;"),
        "font_input" to setOf(
            "LX/8e0;->A06(Landroid/content/res/ColorStateList;Landroid/content/res/ColorStateList;Landroid/graphics/Typeface;Landroid/graphics/drawable/Drawable;Landroid/text/TextUtils\$TruncateAt;Landroid/text/method/MovementMethod;Landroid/widget/EditText;LX/35s;Ljava/lang/CharSequence;Ljava/lang/CharSequence;Ljava/lang/Integer;Ljava/lang/String;Ljava/util/List;[Ljava/lang/String;IIIIIIIIIIIZZZZ)V",
            "LX/BGr;->A01(Landroid/content/res/ColorStateList;Landroid/content/res/ColorStateList;Landroid/graphics/Typeface;Landroid/graphics/drawable/Drawable;Landroid/text/TextUtils\$TruncateAt;Landroid/text/method/MovementMethod;Landroid/widget/EditText;Ljava/lang/CharSequence;Ljava/lang/CharSequence;Ljava/lang/Integer;Ljava/lang/String;Ljava/util/List;LX/X54;[Ljava/lang/String;FFFIIIIIIIIIIIZZZZZ)V",
            "LX/Jjn;->A04(Landroid/content/res/ColorStateList;Landroid/content/res/ColorStateList;Landroid/graphics/Typeface;Landroid/text/Layout\$Alignment;Landroid/text/TextUtils\$TruncateAt;Landroid/widget/EditText;Landroid/widget/TextView\$OnEditorActionListener;Ljava/lang/CharSequence;Ljava/lang/CharSequence;Ljava/util/List;FFFFIIIIIIIIIIIIIIZZ)V",
        ),
        "font_layout" to setOf("LX/2Xf;->A0D(Landroid/graphics/Typeface;)V"),
        "font_repository" to setOf("LX/62h;->A00(LX/62e;LX/62h;LX/7iG;I)Landroid/graphics/Typeface;"),
        "font_roboto" to setOf("LX/20Y;->A00(Landroid/content/Context;Ljava/lang/Integer;)Landroid/graphics/Typeface;"),
        "friend_requests" to setOf("LX/213;->A08()Z", "LX/2KQ;->A05()Z"),
        "growth" to setOf("LX/213;->A09()Z"),
        "growth_notes" to setOf("Lcom/facebook/presence/note/ui/nux/controller/NotesNuxController;->A01(Landroidx/fragment/app/Fragment;LX/SSj;Ljava/util/List;LX/4GZ;Lkotlin/jvm/functions/Function1;)Ljava/lang/Object;"),
        "growth_story_card" to setOf("Lcom/facebook/messaging/montage/viewer/MontageViewerFragment;->A15(Lcom/facebook/messaging/montage/viewer/MontageViewerFragment;)Z"),
        "hide_read_receipts" to setOf("LX/8wm;->A00(Lcom/facebook/auth/usersession/FbUserSession;LX/8wm;Ljava/lang/String;)V"),
        "inbox_promotions" to setOf("LX/273;->A0J()Z", "LX/273;->A0K()Z"),
        "keep_unsent" to setOf("LX/Vz4;->A02(Landroid/content/Intent;Lcom/facebook/auth/usersession/FbUserSession;Ljava/lang/String;Ljava/util/Map;)V"),
        "mark_read_action" to setOf("LX/GJn;->A0V()Z", "LX/I6K;->A0F()Z"),
        "mark_read_bar" to setOf(
            "LX/5yE;->Dbb(Lcom/facebook/messaging/model/threadkey/ThreadKey;Lcom/facebook/messaging/send/trigger/NavigationTrigger;LX/5go;Lcom/facebook/xapp/messaging/capability/vector/Capabilities;Ljava/lang/String;Z)V",
            "LX/91X;->A02(Landroid/content/Context;LX/0Vr;Lcom/facebook/auth/usersession/FbUserSession;LX/10f;Lcom/facebook/messaging/model/threadkey/ThreadKey;LX/91W;Lcom/facebook/mig/scheme/interfaces/MigColorScheme;Lcom/facebook/xapp/messaging/capability/vector/Capabilities;Lcom/facebook/xapp/messaging/map/HeterogeneousMap;LX/Bgm;Lcom/google/common/collect/ImmutableList\$Builder;Lcom/google/common/collect/ImmutableList;Ljava/lang/Integer;Ljava/lang/Long;Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IIIZZZZZZ)Ljava/lang/Object;",
        ),
        "mark_read_click" to setOf("LX/BNb;->onClick(Landroid/view/View;)V"),
        "menu_settings" to setOf(
            "LX/8Yj;->A1l()V", "LX/GGH;->B07(LX/0SK;)Ljava/util/ArrayList;", "LX/GH0;->onClick(Landroid/view/View;)V",
            "LX/LkJ;->CHZ(LX/IlA;I)V", "LX/LkY;->A0H(Ljava/util/List;)V",
        ),
        "message_log" to setOf("Lcom/facebook/messaging/notify/type/NewMessageNotification;-><init>(Lcom/facebook/messaging/accountswitch/model/MessengerAccountType;Lcom/facebook/messaging/model/messages/Message;Lcom/facebook/messaging/model/threads/ThreadSummary;LX/8LR;LX/8aH;Lcom/facebook/messaging/push/flags/ServerMessageAlertFlags;Lcom/facebook/push/constants/PushProperty;Ljava/lang/Long;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/util/Map;ZZZZZZZ)V"),
        "moments" to setOf("LX/GGI;->A05()Z", "LX/I5w;->A05()Z"),
        "mqtt_telemetry" to setOf(
            "LX/0lm;->A0N(Ljava/lang/String;)V",
            "LX/0lm;->A0R(LX/0rE;LX/0qv;Ljava/lang/Integer;Ljava/lang/String;Ljava/lang/String;[BIJ)LX/0q7;",
        ),
        "nearby_scans" to setOf(
            "LX/2tj;->A03(Z)Ljava/util/ArrayList;",
            "Lcom/facebook/location/foreground/ForegroundLocationFrameworkController;->A04(Landroid/content/Context;Lcom/facebook/auth/usersession/FbUserSession;Lcom/facebook/location/foreground/ForegroundLocationFrameworkController;J)V",
        ),
        "original_photo" to setOf(
            "Lcom/facebook/msys/mci/transcoder/DefaultMediaTranscoder;->decodeBitmap(Ljava/lang/String;DD)Landroid/graphics/Bitmap;",
            "Lcom/facebook/msys/mci/transcoder/DefaultMediaTranscoder;->getTranscodeConfig()Ljava/util/Map;",
            "Lcom/facebook/msys/mci/transcoder/DefaultMediaTranscoder;->transcodeImage(Ljava/lang/String;DDLjava/lang/String;Ljava/util/Map;)[B",
            "Lcom/facebook/msys/mci/transcoder/DefaultMediaTranscoder;->transcodeImageAsync(Ljava/lang/String;DDLjava/lang/String;Ljava/util/Map;Lcom/facebook/msys/mci/TranscodeImageCompletionCallback;)V",
        ),
        "original_video" to setOf("Lcom/facebook/msys/mci/transcoder/DefaultMediaTranscoder;->A05(Lcom/facebook/msys/mci/TranscodeVideoCompletionCallback;Lcom/facebook/msys/mci/VideoEdits;Ljava/lang/String;Ljava/lang/String;Ljava/util/Map;)V"),
        "people" to setOf("LX/213;->A0B()Z", "LX/2KQ;->A07()Z"),
        "people_inbox_refresh" to setOf("Lcom/facebook/messaging/msys/threadlist/plugins/core/itemsupplier/ThreadListItemSupplierImplementation;->A0C()Lcom/google/common/collect/ImmutableList;"),
        "people_jewel" to setOf("LX/Uhb;->A02(LX/3o6;LX/Uhb;)Z"),
        "people_list_end" to setOf("LX/213;->A0A()Z", "LX/2KQ;->A06()Z"),
        "people_search" to setOf("LX/GDh;->DVy(LX/HqZ;Ljava/lang/Object;)LX/HuJ;"),
        "people_story" to setOf("Lcom/facebook/messaging/montage/viewer/MontageViewerFragment;->A0X(Lcom/facebook/messaging/montage/viewer/MontageViewerFragment;)V"),
        "people_tab" to setOf("LX/FYa;->A01(LX/FYa;)V"),
        "phone_id_sync" to setOf("LX/1HB;->A03(LX/1HB;)Ljava/util/ArrayList;", "LX/1HB;->A06(Ljava/lang/String;)V"),
        "pinned_chats" to setOf("LX/2qf;->A00(Lcom/facebook/auth/usersession/FbUserSession;)I"),
        "quiet_call_notification" to setOf("Lcom/facebook/rtc/notification/RtcNotificationForegroundService;->A00(Landroid/app/Notification;Landroid/content/Context;LX/AZg;LX/Ahb;LX/AFu;LX/R8C;Ljava/lang/Integer;Ljava/lang/String;IZZZ)V"),
        "quiet_call_ring" to setOf("LX/AES;->run()V"),
        "recent_media" to setOf("LX/9eA;->A01(LX/6NI;Lkotlin/jvm/functions/Function0;)V"),
        "reels_badge" to setOf("LX/4WJ;->A09(LX/4WJ;)Z"),
        "reply_read" to setOf("LX/9Tt;->A01(Lcom/facebook/auth/usersession/FbUserSession;LX/8hs;LX/9BR;Lcom/facebook/xapp/messaging/capability/vector/Capabilities;LX/Bga;LX/99n;LX/9BQ;LX/Bdg;LX/AZV;LX/6NI;LX/97B;Lcom/facebook/xapp/messaging/composer/send/event/SendMessageEvent;Lcom/facebook/xapp/messaging/map/HeterogeneousMap;LX/5gq;Ljava/lang/String;)V"),
        "save_stories" to setOf("LX/NZc;->onClick(Landroid/view/View;)V"),
        "screenshot_viewers" to setOf(
            "Lcom/facebook/messaging/media/ephemeralmedia/viewer/EphemeralMediaViewerFragment;->A1K(Landroid/os/Bundle;)Landroid/app/Dialog;",
            "Lcom/facebook/messaging/media/ephemeralmedia/viewer/EphemeralMediaViewerFragment;->onResume()V",
            "Lcom/facebook/messaging/quicksnap/consumption/viewer/MsgrQuicksnapViewerFragment;->onCreateView(Landroid/view/LayoutInflater;Landroid/view/ViewGroup;Landroid/os/Bundle;)Landroid/view/View;",
        ),
        "stories" to setOf("LX/1zU;->A00()Z"),
        "story_hold" to setOf("LX/LZ2;->CNI()V"),
        "subtabs" to setOf("LX/2Jk;->run()V"),
        "suggested_replies" to setOf("LX/6N5;->A06(LX/6N5;)Z", "LX/6O4;->A05(LX/6O4;)Z", "LX/Lfh;->A03()Z"),
        "system_camera" to setOf(
            "LX/0Hx;->A01(Landroid/content/Context;Landroid/net/Uri;)Landroid/content/res/AssetFileDescriptor;",
            "LX/15S;->A00(Landroid/content/Context;Landroid/net/Uri;)Ljava/lang/Boolean;",
            "LX/5qa;->Did(Lcom/facebook/messaging/montage/composer/model/MontageComposerFragmentParams;Lcom/facebook/messaging/send/trigger/NavigationTrigger;)V",
            "LX/98s;->A00(Landroid/content/Context;Landroid/os/Bundle;Landroidx/fragment/app/Fragment;Lcom/facebook/auth/usersession/FbUserSession;Lcom/facebook/messaging/model/threadkey/ThreadKey;LX/G9Z;)LX/Bh6;",
            "LX/99A;->BkO(Lcom/facebook/xapp/messaging/capability/vector/Capabilities;LX/AZV;LX/6NI;Lcom/facebook/xapp/messaging/composer/event/ComposerEvent;)V",
            "LX/AaX;->C0w(Landroid/os/Bundle;LX/6NI;)Z", "LX/AwA;->Bpf(LX/4Jq;Ljava/lang/String;)V",
        ),
        "tab_badges" to setOf("LX/1n1;->A00(Ljava/lang/String;)LX/38i;"),
        "tab_config" to setOf("LX/1nk;->A02(Landroid/content/Context;Lcom/facebook/auth/usersession/FbUserSession;)LX/1oC;"),
        "tab_notifications" to setOf("LX/1n0;->A09(LX/1n0;)Z"),
        "tab_people" to setOf("LX/1n0;->A0E(LX/1n0;)Z"),
        "text_style" to setOf("LX/8eA;->onFocusChanged(ZILandroid/graphics/Rect;)V"),
        "typing" to setOf("LX/AMI;->run()V"),
        "typing_mailbox" to setOf("LX/4jh;->A0M(Ljava/lang/String;Z)LX/4Uk;"),
        "unsent_indicator" to setOf("LX/UOr;->Bbb(I)Ljava/lang/String;"),
        "wifi_identity" to setOf(
            "LX/2tj;->A02()LX/Vpm;", "LX/368;->AoO()Ljava/lang/String;", "LX/368;->BG1()J",
            "LX/AFl;->A03()Ljava/lang/String;", "LX/AFl;->A04(Ljava/util/Map;)V",
            "LX/NOG;->A01(LX/NOG;)Ljava/lang/String;",
        ),
        "xhe_audio" to setOf("LX/OBx;->call()Ljava/lang/Object;"),
        "xhe_transcode" to setOf("Lcom/facebook/msys/mci/transcoder/DefaultMediaTranscoder;->A05(Lcom/facebook/msys/mci/TranscodeVideoCompletionCallback;Lcom/facebook/msys/mci/VideoEdits;Ljava/lang/String;Ljava/lang/String;Ljava/util/Map;)V"),
    ),
    pluginSentinel = "LX/1el;->A03:Ljava/lang/Object;",
    preferenceGetter = "Lcom/facebook/prefs/shared/FbSharedPreferences;->Ajc(LX/1Ar;Z)Z",
    peopleKey = "LX/FUp;->A01:LX/1As;",
    peopleFlagCheck = "Lcom/facebook/mobileconfig/factory/MobileConfigUnsafeContext;->A09(Ljava/lang/Object;J)Z",
    subtabsSupplier = "LX/2Jk;->A00:Lcom/facebook/messaging/inboxsubtabs/plugins/subtabs/itemsupplier/InboxSubtabsItemSupplierImplementation;",
    browserPreferenceKey = "LX/1DV;->A1W:LX/1Ar;",
    browserPreferenceIndex = 34,
    bubbleCapabilityGetter = "LX/1lr;->A03(Lcom/facebook/auth/usersession/FbUserSession;I)Z",
    bubbleRolloutGetter = "Lcom/facebook/mobileconfig/factory/MobileConfigUnsafeContext;->AjY(J)Z",
    nativeBubbleRoutes = "LX/8fS;->A02(Landroid/graphics/Bitmap;LX/0NZ;Lcom/facebook/auth/usersession/FbUserSession;LX/8fW;Lcom/facebook/messaging/model/threads/ThreadSummary;LX/7k9;Lcom/facebook/push/constants/PushProperty;Z)V|LX/8fU;->A04(Landroid/content/Context;Landroid/graphics/Bitmap;Lcom/facebook/messaging/model/threadkey/ThreadKey;Ljava/lang/String;)LX/8fW;|LX/HxB;->A04(Landroid/content/Context;Landroid/graphics/Bitmap;Lcom/facebook/auth/usersession/FbUserSession;LX/HxB;Lcom/facebook/messaging/model/threads/ThreadSummary;)Z",
    nativeCommunityInbox = "LX/1yA;->A0L(LX/1yA;LX/1zC;Ljava/lang/String;Z)V|LX/294;-><init>(Lcom/facebook/auth/usersession/FbUserSession;LX/28K;LX/28d;LX/292;LX/26t;LX/28z;LX/4AH;LX/4Cr;LX/1yn;Lcom/facebook/mig/scheme/interfaces/MigColorScheme;LX/Bmh;Lcom/google/common/collect/ImmutableList;Ljava/lang/String;Lkotlin/jvm/functions/Function1;Z)V|LX/1z7;->A01()LX/1yn;|LX/1yA;->A0M(LX/1yA;LX/1yn;)V|LX/2Kj;->A03:Lcom/facebook/messaging/model/threads/ThreadSummary;|LX/2lG;->A03(Lcom/facebook/messaging/model/threads/ThreadSummary;)Z|Lcom/facebook/messaging/model/threadkey/ThreadKey;->A0l(Lcom/facebook/messaging/model/threadkey/ThreadKey;)Z|Lcom/facebook/messaging/model/threadkey/ThreadKey;->A1H()Z|LX/1yn;->A0F:LX/1yn;|LX/294;->\$prefixOffsetCallback:LX/4AH;|LX/1yS;->A00:LX/1yA;|LX/1yA;->A0a:LX/1z7;|LX/1z7;->A07:LX/1z9;|LX/1z9;->A01:LX/1zP;|LX/1zP;->A00:LX/1zO;|LX/1zO;->A03()LX/1BR;|LX/1BR;->A0K:LX/1BR;",
)

/** The profile an unknown build is checked against, so its exact hooks refuse it. */
internal val BASE_PROFILE = PROFILE_346814894

/** Each supported build's profile, by version code. Builds that share a mapping share a profile. */
internal val controlProfiles: Map<Int, ControlProfile> = mapOf(
    346814894 to PROFILE_346814894,
)

/** The table [controlProfileFor] reads. Tests that run the patch on a synthetic APK swap it. */
internal var controlProfilesInUse: Map<Int, ControlProfile> = controlProfiles

/** An unknown build gets the base profile, whose exact hooks then refuse it. */
internal fun controlProfileFor(versionCode: String?, profiles: Map<Int, ControlProfile> = controlProfilesInUse): ControlProfile =
    versionCode?.toIntOrNull()?.let(profiles::get) ?: BASE_PROFILE

/** The profile of the APK being patched. The settings extension sets it before any control runs. */
internal var activeProfile: ControlProfile = BASE_PROFILE
