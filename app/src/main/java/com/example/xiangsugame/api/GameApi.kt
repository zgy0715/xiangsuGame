package com.example.xiangsugame.api

import com.example.xiangsugame.api.dto.BankResponse
import com.example.xiangsugame.api.dto.CreateRoomResponse
import com.example.xiangsugame.api.dto.DailyLevelResponse
import com.example.xiangsugame.api.dto.LeaderboardResponse
import com.example.xiangsugame.api.dto.LevelDto
import com.example.xiangsugame.api.dto.LoginRequest
import com.example.xiangsugame.api.dto.LoginResponse
import com.example.xiangsugame.api.dto.MeResponse
import com.example.xiangsugame.api.dto.MyPuzzlesResponse
import com.example.xiangsugame.api.dto.NicknameRequest
import com.example.xiangsugame.api.dto.NicknameResponse
import com.example.xiangsugame.api.dto.SendCodeRequest
import com.example.xiangsugame.api.dto.SendCodeResponse
import com.example.xiangsugame.api.dto.SubmitRecordRequest
import com.example.xiangsugame.api.dto.SubmitRecordResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 服务端 REST 接口声明(与 server/ 各 router 一一对应)。
 * baseUrl 以 "/" 结尾(由 ApiClient 保证),相对路径不带前导斜杠。
 */
interface GameApi {

    // ---- 认证(无用户名密码:邮箱 → 6 位验证码 → Bearer token) ----
    @POST("api/auth/send-code")
    suspend fun sendCode(@Body body: SendCodeRequest): SendCodeResponse

    @POST("api/auth/login")
    suspend fun login(@Body body: LoginRequest): LoginResponse

    @GET("api/auth/me")
    suspend fun me(): MeResponse

    @POST("api/auth/nickname")
    suspend fun updateNickname(@Body body: NicknameRequest): NicknameResponse

    @POST("api/auth/logout")
    suspend fun logout(): Unit

    // ---- 谜题(游戏从网络获取) ----
    @GET("api/puzzles/bank")
    suspend fun bank(
        @Query("difficulty") difficulty: String? = null,
        @Query("offset") offset: Int = 0,
        @Query("limit") limit: Int = 20,
    ): BankResponse

    @GET("api/puzzles/daily")
    suspend fun daily(@Query("date") date: String? = null): DailyLevelResponse

    @GET("api/puzzles/{id}")
    suspend fun puzzle(@Path("id") id: Int): LevelDto

    // ---- 对战房间 ----
    @POST("api/rooms")
    suspend fun createRoom(): CreateRoomResponse

    // ---- 排行榜 ----
    @POST("api/leaderboard/submit")
    suspend fun submitRecord(@Body body: SubmitRecordRequest): SubmitRecordResponse

    @GET("api/leaderboard")
    suspend fun leaderboard(
        @Query("puzzleId") puzzleId: Int,
        @Query("source") source: String? = null,
        @Query("limit") limit: Int = 10,
    ): LeaderboardResponse

    /** 我打过分的题目列表(含网络对战里的内置关),供排行榜"选题目看榜"。 */
    @GET("api/leaderboard/my-puzzles")
    suspend fun myPuzzles(@Query("limit") limit: Int = 20): MyPuzzlesResponse
}
