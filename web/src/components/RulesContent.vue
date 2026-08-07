<script setup lang="ts">
import { computed } from 'vue'
import { DEFAULT_SETTINGS } from '@/game/settings'
import type { GameSettings } from '@/game/settings'

/** 玩法/道具/规则介绍内容，登录页与房间/对局内复用；part 可只渲染一段。
 *  settings 传入当前房间规则时缩圈/怪物/道具相关文案按配置动态渲染，缺省用默认值。 */
const props = defineProps<{
  part?: 'rules' | 'items'
  settings?: GameSettings
}>()

const s = computed(() => props.settings ?? DEFAULT_SETTINGS)

const items = [
  { icon: '?:', name: 'Elvis', desc: '+1 护盾：抵挡下一次伤害' },
  { icon: 'val', name: 'Immutable', desc: '3 秒免疫所有伤害' },
  { icon: '⚡', name: 'Coroutines', desc: '永久加速 30%' },
  { icon: '~', name: 'Flow', desc: '+12 子弹' },
  { icon: '!!', name: 'NotNull', desc: '攻击距离 +3 格（可叠加）' },
  { icon: '⏩', name: 'launch', desc: '攻速提升：冷却 -0.1s（可叠加）' },
  { icon: 'var', name: 'var', desc: '回复 5 点血量' },
]
</script>

<template>
  <div class="rules">
    <template v-if="!part || part === 'rules'">
    <section class="rules__section">
      <h3>🎮 玩法</h3>
      <p>俯视角大逃杀：移动 + 攻击键，躲怪、杀怪、吃道具、互殴、跑圈，<b>活到最后</b>。</p>
      <ul>
        <li><b>手机</b>：左侧摇杆移动，右侧攻击摇杆（推住 = 瞄准 + 连发）</li>
        <li><b>电脑</b>：<code>WASD</code> 移动，<b>鼠标瞄准 + 左键攻击</b>，<code>E</code> 表情</li>
      </ul>
    </section>

    <section class="rules__section">
      <h3>⏱️ 规则</h3>
      <ul>
        <li>
          <b>缩圈即倒计时</b>：开局静置 {{ s.firstIdle }}s，共 {{ s.shrinkTargets.length }} 轮（收缩
          {{ s.shrinkTime }}s + 间隔 {{ s.shrinkCooldown }}s），下一圈位置随机；<b>最终圈塌缩到中心点</b>，撑到最后即胜利
        </li>
        <li>出圈 = <b class="warn">被 GC 标记回收</b>，持续掉血，圈越小掉得越快</li>
        <li>
          血量 100；攻击消耗<b>子弹</b>（共 300 发，每发攻击耗 1），命中怪物 -25 血、命中玩家 -2 血（写死）{{ s.friendlyFire ? '' : '，本房间队友伤害已关闭' }}、<b>还能改变怪物的方向</b>
        </li>
        <li>
          <b>积分制</b>：子弹命中怪物 +1 分、补刀击杀 <b class="warn">+2 分</b>，活到最后血量 ×10% 计入总分，<b>按积分排名</b>
        </li>
        <li>
          <b>怪物</b>：<b class="warn">NPE</b>（红眼死神）直线穿行，子弹能偏转它，撞你 -{{ s.monsterTouchDamage }} 血；<b>SOE</b>（蓝色漩涡）血厚，自带<b>减速光环</b>，靠近会被拖到半速以下
        </li>
        <li>巨石 <code>{ }</code> 和 <code>TODO</code> 石柱是永久掩体：<b>挡人也挡子弹</b></li>
        <li v-if="s.bombsEnabled">
          <b>轰炸区 = OOM</b>：红圈充能 2.5s 后爆炸，满地 <code>OutOfMemoryError</code> 横飞——别站里面（怪也会被炸）
        </li>
        <li v-else><b>轰炸区</b>：本房间已关闭</li>
      </ul>
    </section>
    </template>

    <section v-if="!part || part === 'items'" class="rules__section">
      <h3>🛡️ 道具（Kotlin 特性）</h3>
      <p class="rules__items-note">场上道具上限 {{ s.itemCount }} 个，随时间补充（只刷安全区内，5 秒未拾取会消失）</p>
      <div class="rules__items">
        <div v-for="it in items" :key="it.name" class="rules__item">
          <span class="rules__item-icon">{{ it.icon }}</span>
          <div>
            <b>{{ it.name }}</b>
            <p>{{ it.desc }}</p>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<style lang="scss" scoped>
@use '@/styles/variables' as *;
@use '@/styles/mixins' as *;

.rules {
  text-align: left;

  &__section {
    margin-bottom: 20px;

    h3 {
      color: $primary-hover;
      margin-bottom: 8px;
      font-size: 16px;
    }

    p,
    li {
      color: $text-dim;
      font-size: 14px;
      line-height: 1.7;

      b {
        color: $text-main;
      }

      code {
        background: rgba(127, 82, 255, 0.18);
        color: $primary-hover;
        padding: 1px 6px;
        border-radius: 4px;
        font-size: 13px;
      }

      .warn {
        color: $hp-red;
      }
    }

    ul {
      padding-left: 18px;
    }
  }

  &__items-note {
    margin: -4px 0 8px;
    font-size: 12px;
    color: $text-dim;
  }

  &__items {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 8px;

    @include mobile {
      grid-template-columns: 1fr;
    }
  }

  &__item {
    display: flex;
    gap: 10px;
    align-items: flex-start;
    background: rgba(127, 82, 255, 0.08);
    border-radius: 8px;
    padding: 8px 10px;

    &-icon {
      min-width: 28px;
      height: 28px;
      @include flex-center;
      background: rgba(127, 82, 255, 0.25);
      border-radius: 6px;
      font-weight: bold;
      color: $primary-hover;
      font-size: 13px;
    }

    b {
      font-size: 13px;
      color: $text-main;
    }

    p {
      font-size: 12px;
      line-height: 1.4;
    }
  }
}
</style>
