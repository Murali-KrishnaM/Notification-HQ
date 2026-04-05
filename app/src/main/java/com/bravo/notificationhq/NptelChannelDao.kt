package com.bravo.notificationhq

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Delete
import androidx.room.Update

@Dao
interface NptelChannelDao {

    @Query("SELECT * FROM nptel_channels_table ORDER BY label ASC")
    fun getAllChannels(): List<NptelChannelModel>

    @Insert
    fun insertChannel(channel: NptelChannelModel)

    @Update
    fun updateChannel(channel: NptelChannelModel)

    @Delete
    fun deleteChannel(channel: NptelChannelModel)
}