package io.github.vincekruger.whatsapp_stickers;

import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.content.IntentFilter;
import androidx.annotation.NonNull;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import static io.github.vincekruger.whatsapp_stickers.StickerPackActivity.ADD_PACK;
import java.util.HashMap;
import java.util.Map;

public class WhatsAppStickersPlugin extends BroadcastReceiver implements FlutterPlugin, MethodCallHandler, ActivityAware {
    private final String TAG = "WhatsAppStickersPlugin";
    private MethodChannel channel;
    private Context context;
    private ActivityPluginBinding activityBinding;
    private StickerPackActivity activityResultListener;

    public WhatsAppStickersPlugin() {}

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        activityBinding = binding;
        activityResultListener = new StickerPackActivity(context);
        binding.addActivityResultListener(activityResultListener);
    }

    @Override
    public void onDetachedFromActivity() {
        if (activityBinding != null) {
            activityBinding.removeActivityResultListener(activityResultListener);
        }
        activityBinding = null;
        activityResultListener = null;
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity();
    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
        onAttachedToActivity(binding);
    }

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        context = flutterPluginBinding.getApplicationContext();
        channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), "io.github.vincekruger/whatsapp_stickers");
        channel.setMethodCallHandler(this);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(StickerPackActivity.ACTION_STICKER_PACK_RESULT);
        intentFilter.addAction(StickerPackActivity.ACTION_STICKER_PACK_ERROR);
        LocalBroadcastManager.getInstance(context).registerReceiver(this, intentFilter);
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        onDetachedFromActivity();
        if (channel != null) {
            channel.setMethodCallHandler(null);
            LocalBroadcastManager.getInstance(context).unregisterReceiver(this);
            channel = null;
        }
    }

    @Override
    public void onMethodCall(MethodCall call, Result result) {
        try {
            switch (call.method) {
                case "getPlatformVersion":
                    result.success("Android " + android.os.Build.VERSION.RELEASE);
                    break;
                case "isWhatsAppInstalled":
                    result.success(WhitelistCheck.isWhatsAppInstalled(context));
                    break;
                case "isWhatsAppConsumerAppInstalled":
                    result.success(WhitelistCheck.isWhatsAppConsumerAppInstalled(context.getPackageManager()));
                    break;
                case "isWhatsAppSmbAppInstalled":
                    result.success(WhitelistCheck.isWhatsAppSmbAppInstalled(context.getPackageManager()));
                    break;
                case "launchWhatsApp":
                    Intent launchIntent = context.getPackageManager()
                            .getLaunchIntentForPackage(WhitelistCheck.CONSUMER_WHATSAPP_PACKAGE_NAME);
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(launchIntent);
                        result.success(true);
                    } else {
                        result.error("LAUNCH_ERROR", "WhatsApp is not installed", null);
                    }
                    break;
                case "isStickerPackInstalled":
                    String stickerPackIdentifier = call.argument("identifier");
                    if (stickerPackIdentifier != null) {
                        final boolean installed = WhitelistCheck.isWhitelisted(context, stickerPackIdentifier);
                        result.success(installed);
                    } else {
                        result.error("ARGUMENT_ERROR", "Sticker pack identifier is required", null);
                    }
                    break;
                case "addStickerPack":
                    if (activityBinding == null) {
                        result.error("ACTIVITY_ERROR", "Open the app before adding a sticker pack.", null);
                        return;
                    }
                    String whatsAppPackage = call.argument("package");
                    String identifier = call.argument("identifier");
                    String name = call.argument("name");
                    
                    if (identifier == null || name == null) {
                        result.error("ARGUMENT_ERROR", "Identifier and name are required", null);
                        return;
                    }

                    Intent intent = StickerPackActivity.createIntentToAddStickerPack(
                            getContentProviderAuthority(context), identifier, name);
                    
                    if (intent != null) {
                        intent.setPackage(whatsAppPackage != null && !whatsAppPackage.isEmpty() 
                            ? whatsAppPackage 
                            : WhitelistCheck.CONSUMER_WHATSAPP_PACKAGE_NAME);

                        try {
                            activityBinding.getActivity().startActivityForResult(intent, ADD_PACK);
                            result.success(true);
                        } catch (ActivityNotFoundException e) {
                            result.error("ACTIVITY_ERROR", 
                                "Sticker pack not added. Please update to the latest version of WhatsApp.", 
                                e.getMessage());
                        }
                    } else {
                        result.error("INTENT_ERROR", "Failed to create intent for adding sticker pack", null);
                    }
                    break;
                case "updatedStickerPackContentsFile":
                    String packageName = context.getPackageName();
                    String updateIdentifier = call.argument("identifier");
                    if (updateIdentifier != null) {
                        Uri uri = Uri.parse("content://" + packageName + ".stickercontentprovider/metadata/" + updateIdentifier);
                        context.getContentResolver().notifyChange(uri, null);
                        result.success(true);
                    } else {
                        result.error("ARGUMENT_ERROR", "Identifier is required", null);
                    }
                    break;
                default:
                    result.notImplemented();
                    break;
            }
        } catch (Exception e) {
            result.error("UNEXPECTED_ERROR", e.getMessage(), e.getStackTrace().toString());
        }
    }

   @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null || channel == null) {
            return;
        }

        String action = intent.getAction();
        Map<String, Object> content = new HashMap<>();
        
        // Add logging to debug broadcast receiving
        android.util.Log.d(TAG, "Received broadcast action: " + action);
        
        if (action.equals(StickerPackActivity.ACTION_STICKER_PACK_RESULT)) {
            String packAction = intent.getStringExtra(StickerPackActivity.EXTRA_STICKER_PACK_ACTION);
            boolean packResult = intent.getBooleanExtra(StickerPackActivity.EXTRA_STICKER_PACK_RESULT, false);
            
            android.util.Log.d(TAG, "Success broadcast - Action: " + packAction + ", Result: " + packResult);
            
            content.put("action", packAction);
            content.put("result", packResult);
            channel.invokeMethod("onSuccess", content);
        } else if (action.equals(StickerPackActivity.ACTION_STICKER_PACK_ERROR)) {
            String packAction = intent.getStringExtra(StickerPackActivity.EXTRA_STICKER_PACK_ACTION);
            String error = intent.getStringExtra(StickerPackActivity.EXTRA_STICKER_PACK_ERROR);
            
            android.util.Log.d(TAG, "Error broadcast - Action: " + packAction + ", Error: " + error);
            
            content.put("action", packAction);
            content.put("result", false);
            content.put("error", error);
            channel.invokeMethod("onError", content);
        }
    }

    static String getContentProviderAuthority(Context context) {
        return context.getPackageName() + ".stickercontentprovider";
    }
}
