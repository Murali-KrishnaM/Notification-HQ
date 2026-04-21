package com.bravo.notificationhq

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface HostelChannelDao {

    @Query("SELECT * FROM hostel_channels_table ORDER BY label ASC")
    fun getAllChannels(): List<HostelChannelModel>

    @Insert
    fun insertChannel(channel: HostelChannelModel)

    @Update
    fun updateChannel(channel: HostelChannelModel)

    @Delete
    fun deleteChannel(channel: HostelChannelModel)
}