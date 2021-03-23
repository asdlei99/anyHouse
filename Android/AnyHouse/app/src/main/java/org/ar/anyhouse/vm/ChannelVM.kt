package org.ar.anyhouse.vm

import android.content.Context
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import org.ar.anyhouse.sdk.Role
import org.ar.anyhouse.sdk.RtcListener
import org.ar.anyhouse.sdk.RtmListener
import org.ar.anyhouse.sdk.RtcManager
import org.ar.anyhouse.sdk.RtmManager
import org.ar.anyhouse.service.ServiceManager
import org.ar.anyhouse.utils.launch
import org.ar.rtc.Constants
import org.ar.rtc.IRtcEngineEventHandler
import org.ar.rtm.RtmChannelMember
import org.ar.rtm.RtmMessage
import org.json.JSONObject


class ChannelVM : ViewModel() {

    val channelInfo = MutableLiveData<Channel>()

    val observeHostStatus = MutableLiveData<Int>()
    val observeRtcStatus = MutableLiveData<Int>()
    val observeRaisedHandsList = MutableLiveData<MutableList<RaisedHandsMember>>()//举手列表变化通知
    val observeInviteStatus = MutableLiveData<Int>()//邀请变化通知
    val observerSpeakList = MutableLiveData<MutableList<Speaker>>()//发言者列表变化通知
    val observerListenerList = MutableLiveData<MutableList<Listener>>()//听众列表通知
    val observeReceiveInvite = MutableLiveData<Boolean>()//收到邀请通知
    val observeReject = MutableLiveData<String>()//拒绝邀请通知
    val observeMyRoleChange = MutableLiveData<Int>() // 身份改变
    val observerCloseMic = MutableLiveData<Boolean>() //麦克风状态
    val observerSpeakerVolume = MutableLiveData<Array<out IRtcEngineEventHandler.AudioVolumeInfo>?>()//说话者音量大小
    val observerHosterLeave = MutableLiveData<Boolean>() //主持人离开
    val observerTokenPrivilegeWillExpire = MutableLiveData<Boolean>()//token过期


    private val speakerList = mutableListOf<Speaker>() //发言者列表
    private val listenerList = mutableListOf<Listener>()//听众列表
    private val raisedHandsList = mutableListOf<RaisedHandsMember>()//举手列表
    private val serviceManager = ServiceManager.instance // API 管理类


    //自己是否是频道所有者
    fun isMeHost(): Boolean {
        return serviceManager.getSelfInfo()?.userId.equals(getChannelInfo().hostId)
    }

    fun isMe(userId: String): Boolean {
        return getSelf()?.userId == userId
    }

    //获取自己的信息
    fun getSelf(): Self? {
        return serviceManager.getSelfInfo()
    }

    //初始化 SDK
    fun initSDK(context: Context) {
        RtmManager.instance.registerListener(RtmEvent())
        RtcManager.instance.init(context)
        RtcManager.instance.registerListener(RtcEvent())
    }

    //释放 SDK
    private fun releaseSDK() {
        RtcManager.instance.unRegisterListener()
        RtcManager.instance.release()
    }

    //加入RTC频道
    fun joinChannelSDK() {
        RtcManager.instance.joinChannel(
            getToken(), getChannelId(), getSelfId(), if (isMeHost()) {
                Role.HOST
            } else {
                Role.AUDIENCE
            }
        )
    }

    fun leaveChannelSDK() {
        launch({
            ServiceManager.instance.leaveChannel(
                if (isMeHost()) {
                    1
                } else {
                    0
                }, channelInfo.value?.channelId.toString()
            )
            if (isMeHost()) {
                val json = JSONObject().apply {
                    put("action", BroadcastCMD.HOSTER_LEAVE)
                }
                RtmManager.instance.sendChannelMessage(json.toString())
            }
            RtmManager.instance.unregisterListener()
            RtmManager.instance.unSubMember(getChannelId())
            RtmManager.instance.logOut()
            RtmManager.instance.leaveChannel()
            RtcManager.instance.leaveChannel()
        })

    }

    fun muteLocalAudio(mute: Boolean) {
        RtcManager.instance.muteLocalAudio(mute)
        updateLocalAudioState(mute)

    }

    fun changeRoleToSpeaker() {
        RtcManager.instance.changeRoleToSpeaker()
    }

    fun changeRoleToListener() {
        RtcManager.instance.changeRoleToListener()
    }


    fun applyLine() {
        val json = JSONObject().apply {
            put("action", BroadcastCMD.RAISE_HANDS)
            put("userName", getSelf()?.userName)
            put("userIcon", getSelf()?.userIcon)
        }
        RtmManager.instance.sendPeerMessage(channelInfo.value?.hostId.toString(), json.toString())
        updateUserStatusFromHttp(getSelfId(), 1)

    }

    fun cancleApplyLine() {
        val json = JSONObject().apply {
            put("action", BroadcastCMD.CANCLE_RAISE_HANDS)
        }
        RtmManager.instance.sendPeerMessage(channelInfo.value?.hostId.toString(), json.toString())
        updateUserStatusFromHttp(getSelfId(), 0)

    }

    fun inviteLine(userId: String, needHttp: Boolean = false) {
        val json = JSONObject().apply {
            put("action", BroadcastCMD.INVITE_SPEAK)
        }
        RtmManager.instance.sendPeerMessage(userId, json.toString())
        updateInviteStatus(userId)
        if (needHttp) {
            updateUserStatusFromHttp(userId, -1)
        }
    }

    fun rejectLine() {
        val json = JSONObject().apply {
            put("action", BroadcastCMD.REJECT_INVITE)
            put("userName", getSelf()?.userName)
        }
        RtmManager.instance.sendPeerMessage(channelInfo.value?.hostId.toString(), json.toString())
        updateUserStatusFromHttp(getSelfId(), 0)
    }

    fun acceptLine() {
        val json = JSONObject().apply {
            put("action", BroadcastCMD.ACCEPT_INVITE)
        }
        RtmManager.instance.sendPeerMessage(channelInfo.value?.hostId.toString(), json.toString())
        updateUserStatusFromHttp(getSelfId(), 2)
    }


    fun muteRemoteMic(userId: String) {
        val json = JSONObject().apply {
            put("action", BroadcastCMD.CLOSER_MIC)
        }
        RtmManager.instance.sendPeerMessage(userId, json.toString())
    }


    fun changeRoleGuest(userId: String) {
        val json = JSONObject().apply {
            put("action", BroadcastCMD.ROLE_CHANGE_GUEST)
        }
        RtmManager.instance.sendPeerMessage(userId, json.toString())
    }

    //设置邀请状态 邀请过了就不再邀请了
    fun updateInviteStatus(userId: String) {
        raisedHandsList.forEachIndexed { index, raisedHandsMember ->
            if (userId == raisedHandsMember.userId) {
                raisedHandsMember.isInvited = true
                observeInviteStatus.value = index
            }
        }
    }



    fun getChannelInfo(): Channel {
        channelInfo.value = serviceManager.getChannelInfo()
        return channelInfo.value!!
    }

    fun getChannelId(): String {
        return serviceManager.getChannelInfo().channelId
    }

    fun getToken(): String {
        return serviceManager.getChannelInfo().rtcToken
    }

    fun getChannelName(): String {
        return serviceManager.getChannelInfo().channelName
    }


    fun getSelfId(): String {
        return getSelf()?.userId.toString()
    }

    fun updateUserStatusFromHttp(userId: String, state: Int) {
        launch({
            ServiceManager.instance.updateUserStatus(getChannelId(), userId, state, it)
        })
    }

    fun getMemberWhenEnterFromHttp() {
        launch({
            val asyncListenerList = ServiceManager.instance.getListenerList(getChannelId(), it)
            var listenerStr = asyncListenerList.await()
            if (listenerStr.code == 0) {
                if (!listenerStr.data.isNullOrEmpty()) {
                    listenerStr.data.forEach {
                        addListener(createListener(it.uid, it.userName, it.avatar))
                    }
                }
            }
            if (isMeHost()) {
                var raisedRep =
                    ServiceManager.instance.getRaisedHandsList(getChannelId(), it).await()
                if (raisedRep.code == 0) {
                    raisedRep.data.forEach {
                        addRaisedHandsMember(
                            RaisedHandsMember(
                                it.uid,
                                it.userName,
                                it.avatar,
                                it.state != 1
                            )
                        )
                    }
                }

            }


        })

    }

    inner class RtmEvent : RtmListener() {


        override fun onMemberJoined(var1: RtmChannelMember?) {
            super.onMemberJoined(var1)
        }

        override fun onMemberLeft(var1: RtmChannelMember?) {
            super.onMemberLeft(var1)
            removeRaisedHandsMember(RaisedHandsMember(var1?.userId.toString()))
            removeListener(Listener.Factory.create(var1?.userId.toString()))
        }

        override fun onMessageReceived(var1: RtmMessage?, var2: RtmChannelMember?) {
            super.onMessageReceived(var1, var2)
            val text = var1?.text
            val json = JSONObject(text)
            when (json.getInt("action")) {
                BroadcastCMD.HOSTER_LEAVE -> {
                    if (!isMeHost()) {
                        observerHosterLeave.value = true
                    }
                }
                BroadcastCMD.USER_INFO -> {
                    launch({
                        val userIcon = json.getInt("userIcon")
                        val userName = json.getString("userName")
                        val userId = json.getString("userId")
                        addListener(createListener(userId, userName, userIcon))
                    })
                }
            }

        }

        override fun onJoinChannelSuccess(channelId: String?) {
            if (!isMeHost()) {
                addListener(createListener())
            }
            //加入rtm频成功后 发送

            launch({
                RtmManager.instance.sendChannelMessage(JSONObject().apply {
                    put("userIcon", getSelf()?.userIcon)
                    put("userName", getSelf()?.userName)
                    put("userId", getSelf()?.userId)
                    put("action", BroadcastCMD.USER_INFO)
                }.toString())
                getMemberWhenEnterFromHttp()
            })


        }

        override fun onMessageReceived(var1: RtmMessage?, var2: String?) {
            super.onMessageReceived(var1, var2)
            val text = var1?.text
            val json = JSONObject(text)
            val action = json.getInt("action")
            when (action) {
                BroadcastCMD.RAISE_HANDS -> {
                    val userName = json.getString("userName")
                    val userIcon = json.getInt("userIcon")
                    addRaisedHandsMember(
                        RaisedHandsMember(
                            var2.toString(),
                            userName,
                            userIcon,
                            false
                        )
                    )
                }
                BroadcastCMD.CANCLE_RAISE_HANDS -> {
                    removeRaisedHandsMember(RaisedHandsMember(var2.toString()))
                }
                BroadcastCMD.INVITE_SPEAK -> {
                    observeReceiveInvite.value = true
                }
                BroadcastCMD.REJECT_INVITE -> {
                    val userName = json.getString("userName")
                    removeRaisedHandsMember(RaisedHandsMember(var2.toString()))
                    observeReject.value = userName
                }
                BroadcastCMD.ACCEPT_INVITE -> {
                    removeRaisedHandsMember(RaisedHandsMember(var2.toString()))
                }
                BroadcastCMD.ROLE_CHANGE_GUEST -> {
                    changeRoleToListener()
                }
                BroadcastCMD.CLOSER_MIC -> {
                    observerCloseMic.value = true
                    muteLocalAudio(true)
                }

            }

        }

        override fun onPeersOnlineStatusChanged(var1: MutableMap<String, Int>?) {
            super.onPeersOnlineStatusChanged(var1)
            var1?.let {
                if (it.containsKey(getChannelId())) {
                    observeHostStatus.value = it[getChannelId()]
                }
            }
        }

    }

    inner class RtcEvent : RtcListener() {
        override fun onJoinChannelSuccess(channel: String?, uid: String?, elapsed: Int) {
            if (isMeHost()) {
                val speaker = Speaker.Factory.create(uid.toString())
                speaker.isHoster = true
                speaker.isOpenAudio = true
                speaker.userName = getSelf()?.userName.toString()
                speaker.userIcon = getSelf()?.userIcon!!
                addSpeaker(speaker)
            }
            RtmManager.instance.joinChannel(getChannelId())
            subHostOnlineStatus()

        }

        override fun onUserJoined(uid: String?, elapsed: Int) {

            launch({
                val userRep = ServiceManager.instance.getUserInfo(uid.toString(), it).await()
                if (userRep.code == 0) {
                    val speaker = Speaker.Factory.create(uid.toString())
                    speaker.isHoster = uid == getChannelId()
                    speaker.userIcon = userRep.data.avatar
                    speaker.userName = userRep.data.userName
                    speaker.isOpenAudio = true
                    addSpeaker(speaker)
                } else {
                    val speaker = Speaker.Factory.create(uid.toString())
                    speaker.isHoster = uid == getChannelId()
                    speaker.userIcon = 1
                    speaker.userName = "未知"
                    speaker.isOpenAudio = true
                    addSpeaker(speaker)
                }
            })

        }

        override fun onUserOffline(uid: String?, reason: Int) {
            removeSpeaker(Speaker.Factory.create(uid.toString()))
            if (reason == 2) {
                launch({
                    val userRep = ServiceManager.instance.getUserInfo(uid.toString(), it).await()
                    if (userRep.code == 0) {
                        addListener(
                            createListener(
                                uid.toString(),
                                userRep.data.userName,
                                userRep.data.avatar
                            )
                        )
                    } else {
                        addListener(createListener(uid.toString(), "未知", 1))
                    }
                })
            }
        }

        override fun onAudioVolumeIndication(
            speakers: Array<out IRtcEngineEventHandler.AudioVolumeInfo>?,
            totalVolume: Int
        ) {
            observerSpeakerVolume.value = speakers
        }

        override fun onRemoteAudioStateChanged(
            uid: String?,
            state: Int,
            reason: Int,
            elapsed: Int
        ) {

            updateSpeakerAudioState(uid.toString(), reason)
        }

        override fun onLocalAudioStateChanged(state: Int, reason: Int) {

        }

        override fun onClientRoleChanged(oldRole: Int, newRole: Int) {
            observeMyRoleChange.value = newRole
            if (newRole == Constants.CLIENT_ROLE_BROADCASTER) {
                removeListener(Listener.Factory.create(getSelf()?.userId.toString()))
                val speaker = Speaker.Factory.create(getSelf()?.userId.toString())
                speaker.isHoster = isMeHost()
                speaker.isOpenAudio = true
                speaker.userIcon = getSelf()?.userIcon!!
                speaker.userName = getSelf()?.userName.toString()
                addSpeaker(speaker)
            } else {
                removeSpeaker(Speaker.Factory.create(getSelf()?.userId.toString()))
                addListener(createListener())
                observeMyRoleChange.value = Constants.CLIENT_ROLE_AUDIENCE //从主持人变游客
                updateUserStatusFromHttp(getSelfId(), 0)
            }


        }

        override fun onTokenPrivilegeWillExpire() {
            observerTokenPrivilegeWillExpire.value = true
        }


        override fun onWarning(code: Int) {
            observeRtcStatus.value = code
        }
    }

    private fun subHostOnlineStatus() {//订阅主播在线状态
        if (!isMeHost()) {
            RtmManager.instance.subMemberOnline(getChannelId())
        }
    }


    private fun getAllSpeaker(): MutableList<Speaker> {
        val newList = mutableListOf<Speaker>()
        speakerList.forEach {//必须深拷贝 不然无法使用DiffUtil
            val speaker = Speaker.Factory.create(it.userId)
            speaker.userName = it.userName
            speaker.isHoster = it.isHoster
            speaker.isOpenAudio = it.isOpenAudio
            speaker.userIcon = it.userIcon
            newList.add(speaker)
        }
        return newList
    }

    private fun getAllListener(): MutableList<Listener> {
        val newList = mutableListOf<Listener>()
        listenerList.forEach {//必须深拷贝 不然无法使用DiffUtil
            val listener = Listener.Factory.create(it.userId)
            listener.userName = it.userName
            listener.isOpenAudio = it.isOpenAudio
            listener.userIcon = it.userIcon
            newList.add(listener)
        }
        return newList
    }

    private fun getAllRaiseHandsMember(): MutableList<RaisedHandsMember> {
        val newList = mutableListOf<RaisedHandsMember>()
        raisedHandsList.forEach {//必须深拷贝 不然无法使用DiffUtil
            val member = RaisedHandsMember(it.userId, it.userName, it.userIcon, it.isInvited)
            newList.add(member)
        }
        return newList
    }

    private fun addSpeaker(speaker: Speaker) {
        if (speakerList.contains(speaker)) {
            var sp = speakerList.find { it.userId == speaker.userId }
            sp?.let {
                speakerList.remove(it)
            }
            if (speaker.isHoster) {
                speakerList.add(0, speaker)
            } else {
                speakerList.add(speaker)
            }
        } else {
            if (speaker.isHoster) {
                speakerList.add(0, speaker)
            } else {
                speakerList.add(speaker)
            }
        }
        observerSpeakList.value = getAllSpeaker()
        removeListener(Listener.Factory.create(speaker.userId))//从听众到说话者 听众列表里有的话需要删除
    }

    private fun addListener(listener: Listener) {//得先检查说话得里面有没有这个人 没有才添加
        var speaker = speakerList.find { it.userId == listener.userId }
        if (speaker == null) {
            listenerList.find { it.userId == listener.userId }?.let {
                listenerList.remove(it)
            }
            listenerList.add(listener)
            observerListenerList.value = getAllListener()
        }
    }

    private fun removeListener(listener: Listener) {
        if (listenerList.contains(listener)) {
            listenerList.remove(listener)
        }
        observerListenerList.value = getAllListener()
    }

    private fun removeSpeaker(speaker: Speaker) {
        if (speakerList.contains(speaker)) {
            speakerList.remove(speaker)
        }
        observerSpeakList.value = getAllSpeaker()
    }

    private fun updateSpeakerAudioState(uid: String, reason: Int) {
        var speaker = speakerList.find { it.userId == uid }
        speaker?.let {
            if (reason == 5) {
                it.isOpenAudio = false
            } else if (reason == 6) {
                it.isOpenAudio = true
            }
            observerSpeakList.value = getAllSpeaker()
        }
    }

    private fun createListener(
        userId: String = getSelfId(),
        userName: String = getSelf()?.userName!!,
        userIcon: Int = getSelf()?.userIcon!!
    ): Listener {
        val member = Listener.Factory.create(userId)
        member.userName = userName
        member.userIcon = userIcon
        return member
    }

    private fun addRaisedHandsMember(raisedHandsMember: RaisedHandsMember) {
        raisedHandsList.find { it.userId == raisedHandsMember.userId }?.let {
            raisedHandsList.remove(it)
        }
        raisedHandsList.add(raisedHandsMember)
        observeRaisedHandsList.value = getAllRaiseHandsMember()
    }

    private fun removeRaisedHandsMember(raisedHandsMember: RaisedHandsMember) {
        raisedHandsList.find { it.userId == raisedHandsMember.userId }?.let {
            raisedHandsList.remove(it)
            observeRaisedHandsList.value = getAllRaiseHandsMember()
        }
    }

    private fun updateLocalAudioState(mute: Boolean) {

        speakerList.find { it.userId == getSelf()?.userId.toString() }?.let {
            it.isOpenAudio = !mute
            observerSpeakList.value = getAllSpeaker()
        }
    }


    override fun onCleared() {
        super.onCleared()
        releaseSDK()
    }
}
