package com.bravo.notificationhq

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Delete
import androidx.room.Update

@Dao
interface PlacementChannelDao {

    @Query("SELECT * FROM placement_channels_table ORDER BY label ASC")
    fun getAllChannels(): List<PlacementChannelModel>

    @Insert
    fun insertChannel(channel: PlacementChannelModel)

    @Update
    fun updateChannel(channel: PlacementChannelModel)

    @Delete
    fun deleteChannel(channel: PlacementChannelModel)
}