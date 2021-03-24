package org.ar.anyhouse.vm


/**
 * 每个房间的信息
 */
class Channel  constructor(var channelId:String, var channelName:String,var hostId:String,var rtmToken:String,var rtcToken:String){


    fun isNull():Boolean
    {
        return channelId.isNullOrEmpty()
    }
}