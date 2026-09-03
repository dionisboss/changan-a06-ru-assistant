.class public Lcom/incall/apps/speechassistant/application/StandNluReceiver;
.super Landroid/content/BroadcastReceiver;
.source "StandNluReceiver.java"

# Emulator stand: `am broadcast -a com.stand.NLU --es q "текст"` -> fire a chat query
# through SpeechTestManager.testCloudNlu (CloudNlu -> mock backend).

.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, Landroid/content/BroadcastReceiver;-><init>()V
    return-void
.end method

.method public onReceive(Landroid/content/Context;Landroid/content/Intent;)V
    .registers 6
    const-string v0, "q"
    invoke-virtual {p2, v0}, Landroid/content/Intent;->getStringExtra(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v0
    if-nez v0, :cond_0
    const-string v0, "привет"
    :cond_0
    const-string v1, "SA_STAND_TRIGGER"
    invoke-static {v1, v0}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I
    invoke-static {}, Lcom/incall/apps/speechassistant/service/SpeechTestManager;->getInstance()Lcom/incall/apps/speechassistant/service/SpeechTestManager;
    move-result-object v1
    const/4 v2, 0x1
    const-string v3, "stand"
    invoke-virtual {v1, v2, v3, v0}, Lcom/incall/apps/speechassistant/service/SpeechTestManager;->testCloudNlu(ILjava/lang/String;Ljava/lang/String;)V
    return-void
.end method
