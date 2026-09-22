import { LevelDto } from './Dtos';
import { Difficulty, Level, difficultyFromName, difficultyName } from '../model/Level';

/**
 * 关卡 DTO ↔ 模型 互转(对应 Android 版 api/dto/Dtos.kt 的 toModel / toDto)。
 *
 * 网络关(题库/每日一题/对战选题)与内置关共用同一个 Level 类型,
 * 所以拿到 DTO 后统一转成 Level,后续求解/校验/绘制零分叉。
 */
export function levelFromDto(dto: LevelDto): Level {
  if (dto.rows <= 0 || dto.cols <= 0
    || dto.clueGrid.length !== dto.rows || dto.answerGrid.length !== dto.rows) {
    throw new Error(`网络关卡数据非法: ${dto.rows}x${dto.cols}`);
  }
  const difficulty: Difficulty = difficultyFromName(dto.difficulty);
  const timed: number = dto.timedLimitSeconds === undefined ? 0 : dto.timedLimitSeconds;
  return new Level(dto.id, dto.name, difficulty, dto.rows, dto.cols,
    dto.clueGrid, dto.answerGrid, timed);
}

/** Level → DTO(对战选题时把整关下发给对手/服务器)。 */
export function levelToDto(level: Level): LevelDto {
  return {
    id: level.id,
    name: level.name,
    difficulty: difficultyName(level.difficulty),
    rows: level.rows,
    cols: level.cols,
    clueGrid: level.clueGrid,
    answerGrid: level.answerGrid,
    timedLimitSeconds: level.timedLimitSeconds,
  };
}
