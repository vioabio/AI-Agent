<template>
  <div class="workbench-page">
    <header class="workbench-header">
      <button class="back-btn" type="button" @click="$router.push('/')">← 返回</button>
      <div class="header-copy">
        <h1>🧭 宝可梦资料工作台</h1>
        <p>版本化事实、用户队伍、活动时效与会话历史</p>
      </div>
      <div class="header-actions">
        <span class="mode-badge">后端事实优先 · 无前端假数据</span>
        <button class="refresh-btn" type="button" :disabled="isRefreshing" @click="reloadAll">
          {{ isRefreshing ? '刷新中…' : '刷新资源' }}
        </button>
      </div>
    </header>

    <main class="workbench-content">
      <section class="trust-banner" aria-label="数据可信边界">
        <strong>可信边界</strong>
        <span>每张卡片只展示服务端返回的已版本化数据。候选、过期、无来源或无权限的内容会明确显示为空态/错误态，不由浏览器补写。</span>
      </section>

      <section class="context-panel panel">
        <div class="panel-heading">
          <div>
            <h2>回答上下文</h2>
            <p>先固定游戏、语言、赛制与 data release，再查询事实或保存队伍。</p>
          </div>
          <span class="context-badge">ContextCard · canonical {{ canonicalBattleFormatId || '待解析' }}</span>
        </div>
        <form class="context-form" @submit.prevent="searchFacts">
          <label>
            游戏版本
            <select v-model="context.gameReleaseId" required>
              <option value="pokemon-scarlet-violet">宝可梦朱/紫</option>
            </select>
          </label>
          <label>
            DLC
            <select v-model="context.dlcId">
              <option value="">本体 / 不指定</option>
              <option value="the-hidden-treasure-of-area-zero">零之秘宝</option>
            </select>
          </label>
          <label>
            语言
            <select v-model="context.language" required>
              <option value="zh-CN">简体中文</option>
              <option value="en-US">English</option>
              <option value="ja-JP">日本語</option>
            </select>
          </label>
          <label>
              赛制
            <select v-model="context.battleFormat" required>
              <option v-for="option in battleFormatOptions" :key="option.alias" :value="option.alias">{{ option.label }}</option>
            </select>
          </label>
          <label>
            规则版本
            <input v-model.trim="context.rulesetId" maxlength="64" required placeholder="例如 casual-v1" />
          </label>
          <label>
            数据版本
            <input v-model.trim="context.dataReleaseId" maxlength="128" required placeholder="例如 pokemon-scarlet-violet-data-v1" />
          </label>
          <button class="primary-btn context-submit" type="submit" :disabled="factsState.status === 'loading'">
            锁定上下文并查询事实
          </button>
        </form>
        <p class="context-note">当前赛制 alias 会在序列化前解析为 canonical battleFormatId；如果服务端返回候选或未审核状态，工作台会保留证据边界并拒绝展示。</p>
      </section>

      <div class="workbench-grid">
        <section class="panel facts-panel" aria-labelledby="facts-title">
          <div class="panel-heading compact">
            <div>
              <h2 id="facts-title">图鉴事实 <span class="card-kind">FactCard</span></h2>
              <p>按 slug 或全国图鉴编号查询，数值始终绑定 data release。</p>
            </div>
            <span class="state-chip" :class="factsState.status">{{ stateLabel(factsState.status) }}</span>
          </div>
          <form class="inline-form" @submit.prevent="searchFacts">
            <label class="grow">
              查询目标
              <input v-model.trim="factQuery" maxlength="128" placeholder="slug，例如 charizard；留空返回版本列表" />
            </label>
            <label class="number-field">
              全国编号
              <input v-model="nationalNo" inputmode="numeric" pattern="[0-9]*" maxlength="6" placeholder="可选" />
            </label>
            <button class="secondary-btn" type="submit" :disabled="factsState.status === 'loading'">查询</button>
          </form>

          <div v-if="factsState.status === 'loading'" class="state-box loading" role="status">正在读取版本化事实…</div>
          <div v-else-if="factsState.status === 'permission'" class="state-box permission" role="alert">需要登录或资源权限才能读取事实（{{ factsState.error }}）。</div>
          <div v-else-if="factsState.status === 'error'" class="state-box error" role="alert">事实读取失败：{{ factsState.error }}<button type="button" @click="searchFacts">重试</button></div>
          <div v-else-if="factsState.status === 'empty'" class="state-box empty">该 data release 没有可展示的事实；候选/未审核数据不会被前端猜测。</div>
          <div v-else-if="facts.length" class="fact-list">
            <article v-for="fact in facts" :key="`${fact.dataReleaseId}:${fact.formId}`" class="fact-card">
              <div class="fact-title-row">
                <div>
                  <h3>{{ fact.speciesNameZh || fact.slug || '未命名宝可梦' }}</h3>
                  <span class="muted">#{{ fact.nationalNo }} · {{ fact.formNameZh || fact.formKey }}</span>
                </div>
                <div class="type-tags">
                  <span class="type-tag">{{ fact.type1 }}</span>
                  <span v-if="fact.type2" class="type-tag">{{ fact.type2 }}</span>
                </div>
              </div>
              <dl class="stats-grid">
                <div><dt>HP</dt><dd>{{ fact.hp }}</dd></div>
                <div><dt>攻击</dt><dd>{{ fact.attack }}</dd></div>
                <div><dt>防御</dt><dd>{{ fact.defense }}</dd></div>
                <div><dt>特攻</dt><dd>{{ fact.spAttack }}</dd></div>
                <div><dt>特防</dt><dd>{{ fact.spDefense }}</dd></div>
                <div><dt>速度</dt><dd>{{ fact.speed }}</dd></div>
              </dl>
              <div class="lineage-block">
                <span>data release：<code>{{ fact.dataReleaseId || '未返回' }}</code></span>
                <span>game release：<code>{{ fact.gameReleaseId || '未返回' }}</code></span>
                <span>状态：{{ fact.releaseStatus || '未返回' }}</span>
                <span>checksum：<code>{{ fact.checksum || '未返回' }}</code></span>
              </div>
              <div class="evidence-block">
                <span>来源：</span>
                <a v-if="safeHttpUrl(fact.sourceUrl)" :href="safeHttpUrl(fact.sourceUrl)" target="_blank" rel="noopener noreferrer">{{ fact.sourceUrl }}</a>
                <span v-else class="muted">服务端未返回可验证 HTTP(S) 来源</span>
                <span class="evidence-text">证据摘录：{{ fact.evidenceText || '当前 Fact DTO 未提供 evidenceText，不能由前端补写。' }}</span>
              </div>
            </article>
          </div>
          <div v-else class="state-box idle">提交上下文后查询事实。</div>
        </section>

        <section class="panel calculation-panel" aria-labelledby="calculation-title">
          <div class="panel-heading compact">
            <div>
              <h2 id="calculation-title">确定性计算 <span class="card-kind">CalculationCard</span></h2>
              <p>属性克制由服务端冻结算法计算；前端不复制属性表，也不把模型常识当作数值结论。</p>
            </div>
            <span class="state-chip" :class="calculationState.status">{{ stateLabel(calculationState.status) }}</span>
          </div>
          <form class="calculation-form" @submit.prevent="runTypeCalculation">
            <label>
              攻击属性
              <select v-model="calculationForm.attackType" required>
                <option v-for="type in pokemonTypes" :key="`attack-${type}`" :value="type">{{ type }}</option>
              </select>
            </label>
            <label class="calculation-defend">
              防御属性（1–2 个，用逗号分隔）
              <input v-model.trim="calculationForm.defendTypes" maxlength="32" required placeholder="例如 草,钢" />
            </label>
            <button class="primary-btn" type="submit" :disabled="calculationState.status === 'loading'">
              {{ calculationState.status === 'loading' ? '计算中…' : '计算倍率' }}
            </button>
          </form>
          <div v-if="calculationState.status === 'loading'" class="state-box loading" role="status">正在读取版本化计算结果…</div>
          <div v-else-if="calculationState.status === 'permission'" class="state-box permission" role="alert">当前身份没有执行计算的权限（{{ calculationState.error }}）。</div>
          <div v-else-if="calculationState.status === 'error'" class="state-box error" role="alert">计算失败：{{ calculationState.error }}<button type="button" @click="runTypeCalculation">重试</button></div>
          <div v-else-if="calculationResult" class="calculation-card" aria-live="polite">
            <div class="calculation-value-row">
              <span>最终倍率</span>
              <strong>{{ formatMultiplier(calculationResult.value) }}×</strong>
            </div>
            <div class="lineage-block">
              <span>data release：<code>{{ calculationResult.dataReleaseId || '未返回' }}</code></span>
              <span>算法版本：<code>{{ calculationResult.calculationVersion || '未返回' }}</code></span>
              <span>攻击：{{ calculationForm.attackType }}</span>
              <span>防御：{{ calculationForm.defendTypes }}</span>
            </div>
            <p class="calculation-note">结果来自后端确定性 Calculator；缺失版本、未知属性或非法输入会被拒绝，不会由前端补写。</p>
          </div>
          <div v-else class="state-box idle">提交上下文后输入属性并计算。</div>
        </section>

        <section class="panel team-panel" aria-labelledby="teams-title">
          <div class="panel-heading compact">
            <div>
              <h2 id="teams-title">我的队伍 <span class="card-kind">TeamMatrixCard</span></h2>
              <p>队伍创建由后端做版本、规则、槽位和主体授权校验。</p>
            </div>
            <span class="state-chip" :class="teamsState.status">{{ stateLabel(teamsState.status) }}</span>
          </div>
          <form class="team-form" @submit.prevent="saveTeam">
            <label>
              队伍名称
              <input v-model.trim="teamForm.name" maxlength="128" required placeholder="例如 朱紫休闲单打" />
            </label>
            <div class="two-fields">
              <label>
                规则键
                <input v-model.trim="teamForm.rulesetId" maxlength="64" required />
              </label>
              <label>
                数据版本
                <input v-model.trim="teamForm.dataReleaseId" maxlength="128" required />
              </label>
            </div>
            <fieldset>
              <legend>成员 formId（1–6 个，来自事实查询结果）</legend>
              <div class="member-grid">
                <label v-for="(_, index) in teamForm.members" :key="index">
                  {{ index + 1 }}
                  <input v-model.trim="teamForm.members[index]" inputmode="numeric" pattern="[0-9]*" maxlength="20" :aria-label="`第 ${index + 1} 个成员 formId`" placeholder="formId" />
                </label>
              </div>
            </fieldset>
            <p v-if="teamFormError" class="form-error" role="alert">{{ teamFormError }}</p>
            <div class="team-form-actions">
              <button class="primary-btn" type="submit" :disabled="teamsState.status === 'saving'">{{ teamsState.status === 'saving' ? '保存中…' : (isEditingTeam ? '更新队伍' : '保存队伍') }}</button>
              <button v-if="isEditingTeam" class="secondary-btn" type="button" @click="cancelEditTeam">取消编辑</button>
            </div>
          </form>

          <div v-if="teamsState.status === 'loading' || teamsState.status === 'saving'" class="state-box loading" role="status">{{ teamsState.status === 'saving' ? '正在提交队伍…' : '正在读取队伍…' }}</div>
          <div v-else-if="teamsState.status === 'permission'" class="state-box permission" role="alert">需要登录或资源权限才能管理队伍（{{ teamsState.error }}）。</div>
          <div v-else-if="teamsState.status === 'error'" class="state-box error" role="alert">队伍操作失败：{{ teamsState.error }}<button type="button" @click="loadTeams">重试</button></div>
          <div v-else-if="teams.length" class="team-list">
            <article v-for="team in teams" :key="team.id || team.teamId" class="team-card">
              <div class="team-card-heading">
                <div>
                  <h3>{{ team.name || '未命名队伍' }}</h3>
                  <span class="muted">#{{ team.id || team.teamId }} · {{ team.rulesetId }} · {{ team.dataReleaseId }}</span>
                </div>
                <div class="team-actions">
                  <button class="edit-link" type="button" @click="editTeam(team)">编辑</button>
                  <button class="danger-link" type="button" @click="removeTeam(team)">删除</button>
                </div>
              </div>
              <ol class="member-list">
                <li v-for="member in (team.members || [])" :key="member.id || `${member.formId}-${member.slot}`">槽位 {{ member.slot }} · formId {{ member.formId }}</li>
              </ol>
            </article>
          </div>
          <div v-else class="state-box empty">当前主体还没有已保存队伍。</div>
        </section>

        <section class="panel activity-panel" aria-labelledby="activities-title">
          <div class="panel-heading compact">
            <div>
              <h2 id="activities-title">活动资讯 <span class="card-kind">ActivityCard</span></h2>
              <p>只展示来源可靠、已发布、在有效窗口内且 freshness 为 FRESH 的内容。</p>
            </div>
            <span class="state-chip" :class="activitiesState.status">{{ stateLabel(activitiesState.status) }}</span>
          </div>
          <div v-if="activitiesState.status === 'loading'" class="state-box loading" role="status">正在读取活动时效…</div>
          <div v-else-if="activitiesState.status === 'permission'" class="state-box permission" role="alert">当前身份没有读取活动资讯的权限（{{ activitiesState.error }}）。</div>
          <div v-else-if="activitiesState.status === 'error'" class="state-box error" role="alert">活动资讯暂不可用：{{ activitiesState.error }}<button type="button" @click="loadActivities">重试</button></div>
          <div v-else-if="activitiesState.status === 'empty'" class="state-box empty">当前上下文没有可展示的活动；过期或未审核内容已被后端隐藏。</div>
          <div v-else-if="activities.length" class="activity-list">
            <article v-for="activity in activities" :key="activity.id" class="activity-card">
              <div class="activity-heading">
                <div>
                  <h3>{{ activity.title }}</h3>
                  <span class="muted">{{ activity.eventType }} · {{ activity.region || '未指定地区' }}</span>
                </div>
                <span class="fresh-badge">{{ activity.freshness || '未返回 freshness' }}</span>
              </div>
              <p>{{ activity.summary || '服务端未提供摘要。' }}</p>
              <div class="activity-meta">
                <span>生效：{{ formatDate(activity.effectiveFrom) }} – {{ formatDate(activity.effectiveTo) }}</span>
                <span>抓取：{{ formatDate(activity.retrievedAt) }}</span>
                <span>信任：{{ activity.trustLevel || '未返回' }} · 许可：{{ activity.license || '未返回' }}</span>
              </div>
              <a v-if="safeHttpUrl(activity.sourceUrl)" class="source-link" :href="safeHttpUrl(activity.sourceUrl)" target="_blank" rel="noopener noreferrer">查看来源 ↗</a>
              <span v-else class="muted">没有可验证来源链接，已隐藏外链。</span>
            </article>
          </div>
          <div v-else class="state-box idle">刷新资源后加载当前版本活动。</div>
        </section>

        <section class="panel favorites-panel" aria-labelledby="favorites-title">
          <div class="panel-heading compact">
            <div>
              <h2 id="favorites-title">我的收藏 <span class="card-kind">FavoriteResource</span></h2>
              <p>收藏只保存服务端认可的 resourceType/resourceKey；主体隔离、幂等和删除权限由后端确认。</p>
            </div>
            <div class="favorite-heading-actions">
              <span class="state-chip" :class="favoritesState.status">{{ stateLabel(favoritesState.status) }}</span>
              <button class="refresh-link" type="button" :disabled="favoritesState.status === 'loading' || favoritesState.status === 'saving'" @click="loadFavorites" aria-label="刷新收藏列表">刷新</button>
            </div>
          </div>
          <form class="favorite-form" @submit.prevent="saveFavorite">
            <label>
              资源类型
              <select v-model="favoriteForm.resourceType" required aria-label="收藏资源类型">
                <option v-for="option in favoriteTypeOptions" :key="option.value" :value="option.value">{{ option.label }}</option>
              </select>
            </label>
            <label class="grow">
              资源键
              <input v-model.trim="favoriteForm.resourceKey" maxlength="256" required aria-label="收藏资源键" placeholder="例如 charizard、team-42 或 notice-2026-01" />
            </label>
            <label class="favorite-label-field">
              展示备注（可选）
              <input v-model.trim="favoriteForm.label" maxlength="256" aria-label="收藏展示备注" placeholder="例如 我的喷火龙" />
            </label>
            <button class="primary-btn" type="submit" :disabled="favoritesState.status === 'saving'">
              {{ favoritesState.status === 'saving' ? '保存中…' : '加入收藏' }}
            </button>
          </form>
          <p v-if="favoriteFormError" class="form-error" role="alert">{{ favoriteFormError }}</p>

          <div v-if="favoritesState.status === 'loading'" class="state-box loading" role="status">正在读取收藏…</div>
          <div v-else-if="favoritesState.status === 'saving'" class="state-box loading" role="status">正在保存收藏…</div>
          <div v-else-if="favoritesState.status === 'permission'" class="state-box permission" role="alert">需要登录或资源权限才能管理收藏（{{ favoritesState.error }}）。</div>
          <div v-else-if="favoritesState.status === 'error'" class="state-box error" role="alert">收藏操作失败：{{ favoritesState.error }}<button type="button" @click="loadFavorites">重试</button></div>
          <div v-else-if="favorites.length" class="favorite-list" aria-live="polite">
            <article v-for="favorite in favorites" :key="favorite.id || favorite.favoriteId || `${favorite.resourceType}-${favorite.resourceKey}`" class="favorite-card">
              <div class="favorite-card-heading">
                <div>
                  <h3>{{ favorite.label || favorite.resourceKey }}</h3>
                  <span class="muted">{{ favoriteTypeLabel(favorite.resourceType) }} · {{ favorite.resourceKey }}</span>
                </div>
                <button class="danger-link" type="button" :aria-label="`删除收藏 ${favorite.label || favorite.resourceKey}`" @click="removeFavorite(favorite)">删除</button>
              </div>
              <small class="favorite-meta">创建：{{ formatDate(favorite.createdAt) }}<span v-if="favorite.updatedAt"> · 更新：{{ formatDate(favorite.updatedAt) }}</span></small>
            </article>
            <button v-if="favoriteCursor" class="load-more-btn" type="button" :disabled="favoritesLoadingMore" @click="loadFavorites({ append: true })">
              {{ favoritesLoadingMore ? '加载中…' : '加载更多收藏' }}
            </button>
            <p v-if="favoritesState.error" class="history-inline-error" role="alert">
              {{ favoritesState.error }}
              <button type="button" @click="loadFavorites({ append: true })">重试</button>
            </p>
          </div>
          <div v-else class="state-box empty">当前主体还没有收藏资源。</div>
        </section>

        <section class="panel history-panel" aria-labelledby="history-title">
          <div class="panel-heading compact">
            <div>
              <h2 id="history-title">会话 / Run 历史 <span class="card-kind">History</span></h2>
              <p>会话列表和完整消息来自服务端；浏览器不会伪造历史答案。</p>
            </div>
            <span class="state-chip" :class="historyState.status">{{ stateLabel(historyState.status) }}</span>
          </div>
          <div v-if="historyState.status === 'loading'" class="state-box loading" role="status">正在读取会话历史…</div>
          <div v-else-if="historyState.status === 'permission'" class="state-box permission" role="alert">需要登录或资源权限才能读取历史（{{ historyState.error }}）。</div>
          <div v-else-if="historyState.status === 'error'" class="state-box error" role="alert">历史读取失败：{{ historyState.error }}<button type="button" @click="loadConversations">重试</button></div>
          <div v-else-if="!conversations.length" class="state-box empty">当前主体还没有服务端会话历史。</div>
          <div v-else class="history-layout">
            <div class="conversation-list" aria-label="会话列表">
              <button v-for="conversation in conversations" :key="conversation.id" type="button" class="conversation-item" :class="{ selected: selectedConversation?.id === conversation.id }" @click="openConversation(conversation)">
                <strong>{{ conversation.title || conversation.id }}</strong>
                <small>{{ formatDate(conversation.updatedAt || conversation.createdAt) }}</small>
              </button>
              <button v-if="conversationCursor" class="load-more-btn" type="button" :disabled="conversationsLoadingMore" @click="loadConversations({ append: true })">
                {{ conversationsLoadingMore ? '加载中…' : '加载更多会话' }}
              </button>
              <p v-if="historyState.error" class="history-inline-error" role="alert">
                {{ historyState.error }}
                <button type="button" @click="loadConversations({ append: true })">重试</button>
              </p>
            </div>
            <div class="message-history" aria-live="polite">
              <div v-if="messagesState.status === 'loading'" class="state-box loading">正在读取消息…</div>
              <div v-else-if="messagesState.status === 'permission'" class="state-box permission">没有读取该会话的权限。</div>
              <div v-else-if="messagesState.status === 'error'" class="state-box error">消息读取失败：{{ messagesState.error }}<button type="button" @click="selectedConversation && openConversation(selectedConversation)">重试</button></div>
              <div v-else-if="!selectedConversation" class="state-box idle">选择左侧会话查看完整消息。</div>
              <div v-else-if="!messages.length" class="state-box empty">该会话没有可展示消息。</div>
              <ol v-else class="message-list">
                <li v-for="message in messages" :key="message.id" :class="['history-message', message.role]">
                  <span class="message-role">{{ message.role === 'assistant' ? 'AI' : message.role === 'user' ? '训练家' : message.role }}</span>
                  <p>{{ message.content }}</p>
                  <small>{{ formatDate(message.createdAt) }}<span v-if="message.runId"> · Run {{ message.runId }}</span></small>
                </li>
              </ol>
            </div>
          </div>
        </section>
      </div>
    </main>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import {
  calculateTypeEffectiveness,
  createPokemonTeam,
  deletePokemonTeam,
  fetchFavorites,
  createFavorite,
  deleteFavorite as deleteFavoriteResource,
  updatePokemonTeam,
  fetchConversationMessages,
  fetchConversations,
  fetchPokemonActivities,
  fetchPokemonFacts,
  fetchPokemonTeams
} from '@/api'
import { BATTLE_FORMAT_OPTIONS, resolveBattleFormat, resolveGameRelease } from '@/api/version-registry'

const battleFormatOptions = BATTLE_FORMAT_OPTIONS
const favoriteTypeOptions = [
  { value: 'pokemon', label: '宝可梦事实' },
  { value: 'team', label: '队伍' },
  { value: 'activity', label: '活动资讯' },
  { value: 'answer', label: '答案' },
  { value: 'rule', label: '规则' }
]
const favoriteTypeLabels = Object.fromEntries(favoriteTypeOptions.map(option => [option.value, option.label]))
const pokemonTypes = ['一般', '火', '水', '电', '草', '冰', '格斗', '毒', '地面', '飞行', '超能力', '虫', '岩石', '幽灵', '龙', '恶', '钢', '妖精']
const canonicalBattleFormatId = computed(() => {
  try { return resolveBattleFormat(context.battleFormat).battleFormatId } catch { return '' }
})

const context = reactive({
  gameReleaseId: 'pokemon-scarlet-violet',
  dlcId: '',
  language: 'zh-CN',
  battleFormat: 'casual-singles',
  rulesetId: 'casual-v1',
  dataReleaseId: 'pokemon-scarlet-violet-data-v1'
})

const factQuery = ref('')
const nationalNo = ref('')
const facts = ref([])
const teams = ref([])
const activities = ref([])
const favorites = ref([])
const favoriteCursor = ref(null)
const favoritesLoadingMore = ref(false)
const conversations = ref([])
const conversationCursor = ref(null)
const conversationsLoadingMore = ref(false)
const messages = ref([])
const calculationResult = ref(null)
const selectedConversation = ref(null)
const isRefreshing = ref(false)
const teamFormError = ref('')
const favoriteFormError = ref('')
const editingTeamId = ref(null)

// 工作台的资源请求都属于当前路由生命周期。每类查询保留自己的
// AbortController，避免快速切换会话/查询时旧响应覆盖新状态，也避免离开
// 路由后仍有 fetch 回调写入已卸载组件。
const pendingControllers = new Set()
let disposed = false
let factsRequestController = null
let calculationRequestController = null
let teamsRequestController = null
let teamMutationRequestController = null
let activitiesRequestController = null
let favoritesRequestController = null
let favoriteMutationRequestController = null
let conversationsRequestController = null
let messagesRequestController = null

const factsState = reactive({ status: 'idle', error: '' })
const teamsState = reactive({ status: 'idle', error: '' })
const activitiesState = reactive({ status: 'idle', error: '' })
const favoritesState = reactive({ status: 'idle', error: '' })
const historyState = reactive({ status: 'idle', error: '' })
const messagesState = reactive({ status: 'idle', error: '' })
const calculationState = reactive({ status: 'idle', error: '' })

const calculationForm = reactive({
  attackType: '火',
  defendTypes: '草,钢'
})

const teamForm = reactive({
  name: '',
  rulesetId: context.rulesetId,
  dataReleaseId: context.dataReleaseId,
  members: ['', '', '', '', '', '']
})

const favoriteForm = reactive({
  resourceType: 'pokemon',
  resourceKey: '',
  label: ''
})

const isEditingTeam = computed(() => editingTeamId.value !== null)

function beginRequest(previous = null) {
  previous?.abort()
  const controller = new AbortController()
  pendingControllers.add(controller)
  return controller
}

function endRequest(controller) {
  pendingControllers.delete(controller)
}

function isAbortError(error) {
  return error?.name === 'AbortError' || error?.code === 20
}

function requestWasSuperseded(controller) {
  return disposed || controller.signal.aborted
}

function stateLabel(status) {
  return {
    idle: '未查询',
    loading: '加载中',
    saving: '保存中',
    ready: '已加载',
    empty: '空结果',
    permission: '无权限',
    error: '错误'
  }[status] || status
}

function resourceItems(payload) {
  if (Array.isArray(payload)) return payload
  if (Array.isArray(payload?.items)) return payload.items
  if (Array.isArray(payload?.data)) return payload.data
  return []
}

function resourcePage(payload) {
  if (Array.isArray(payload)) return { items: payload, nextCursor: null }
  if (payload && typeof payload === 'object') {
    return {
      items: resourceItems(payload),
      nextCursor: typeof payload.nextCursor === 'string' && payload.nextCursor.trim()
        ? payload.nextCursor.trim() : null
    }
  }
  return { items: [], nextCursor: null }
}

function classifyError(state, error, fallback) {
  state.error = error?.message || fallback
  state.status = [401, 403].includes(Number(error?.status)) ? 'permission' : 'error'
}

function markCollection(state, items) {
  state.error = ''
  state.status = items.length ? 'ready' : 'empty'
}

function safeHttpUrl(raw) {
  if (!raw || typeof raw !== 'string') return ''
  try {
    // 来源契约要求绝对 HTTP(S) URL；不把相对路径拼到当前站点，避免把
    // 未经 allowlist 的值误当成可信来源或内部路由。
    const parsed = new URL(raw)
    // 来源链接来自服务端/检索结果；即使协议是 HTTP(S)，也拒绝
    // userinfo（user:password@host），避免把凭据样式内容带入浏览器历史或展示。
    return ['http:', 'https:'].includes(parsed.protocol) && Boolean(parsed.hostname)
      && !parsed.username && !parsed.password
      ? parsed.href : ''
  } catch {
    return ''
  }
}

function formatDate(value) {
  if (!value) return '未提供'
  try {
    const date = new Date(value)
    if (Number.isNaN(date.getTime())) return '时间无效'
    return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(date)
  } catch {
    return '时间无效'
  }
}

function formatMultiplier(value) {
  const numeric = Number(value)
  if (!Number.isFinite(numeric)) return '—'
  return Number.isInteger(numeric)
    ? String(numeric)
    : numeric.toFixed(2).replace(/0+$/, '').replace(/\.$/, '')
}

function validateContext() {
  if (!context.gameReleaseId || !context.language || !context.dataReleaseId || !context.rulesetId) {
    throw new Error('请先填写游戏、语言、规则版本和数据版本')
  }
  resolveGameRelease(context.gameReleaseId)
  resolveBattleFormat(context.battleFormat)
}

async function searchFacts() {
  const controller = beginRequest(factsRequestController)
  factsRequestController = controller
  factsState.status = 'loading'
  factsState.error = ''
  try {
    validateContext()
    const parsedNo = nationalNo.value.trim() ? Number(nationalNo.value.trim()) : undefined
    if (parsedNo !== undefined && (!Number.isInteger(parsedNo) || parsedNo <= 0)) throw new Error('全国编号必须是正整数')
    const result = await fetchPokemonFacts({
      dataReleaseId: context.dataReleaseId,
      slug: factQuery.value || undefined,
      nationalNo: parsedNo,
      limit: 50,
      signal: controller.signal
    })
    if (requestWasSuperseded(controller)) return
    facts.value = resourceItems(result)
    markCollection(factsState, facts.value)
  } catch (error) {
    if (isAbortError(error) || requestWasSuperseded(controller)) return
    facts.value = []
    classifyError(factsState, error, '事实查询失败')
  } finally {
    if (factsRequestController === controller) factsRequestController = null
    endRequest(controller)
  }
}

async function runTypeCalculation() {
  const controller = beginRequest(calculationRequestController)
  calculationRequestController = controller
  calculationState.status = 'loading'
  calculationState.error = ''
  calculationResult.value = null
  try {
    validateContext()
    const defendTypes = calculationForm.defendTypes
      .split(/[，,\s]+/)
      .map(value => value.trim())
      .filter(Boolean)
    if (defendTypes.length < 1 || defendTypes.length > 2) throw new Error('请输入 1–2 个防御属性')
    const result = await calculateTypeEffectiveness({
      dataReleaseId: context.dataReleaseId,
      attackType: calculationForm.attackType,
      defendTypes,
      signal: controller.signal
    })
    if (requestWasSuperseded(controller)) return
    if (!result || typeof result !== 'object') throw new Error('服务端未返回结构化计算结果')
    calculationResult.value = result
    calculationState.status = 'ready'
  } catch (error) {
    if (isAbortError(error) || requestWasSuperseded(controller)) return
    calculationResult.value = null
    classifyError(calculationState, error, '属性克制计算失败')
  } finally {
    if (calculationRequestController === controller) calculationRequestController = null
    endRequest(controller)
  }
}

async function loadTeams() {
  const controller = beginRequest(teamsRequestController)
  teamsRequestController = controller
  teamsState.status = 'loading'
  teamsState.error = ''
  try {
    const result = await fetchPokemonTeams({ limit: 50, signal: controller.signal })
    if (requestWasSuperseded(controller)) return
    teams.value = resourceItems(result)
    markCollection(teamsState, teams.value)
  } catch (error) {
    if (isAbortError(error) || requestWasSuperseded(controller)) return
    teams.value = []
    classifyError(teamsState, error, '队伍读取失败')
  } finally {
    if (teamsRequestController === controller) teamsRequestController = null
    endRequest(controller)
  }
}

async function saveTeam() {
  const controller = beginRequest(teamMutationRequestController)
  teamMutationRequestController = controller
  teamFormError.value = ''
  try {
    const members = teamForm.members
      .map((value, index) => ({ value: value.trim(), slot: index + 1 }))
      .filter(member => member.value)
      .map(member => ({ formId: Number(member.value), slot: member.slot }))
    if (!teamForm.name) throw new Error('队伍名称不能为空')
    if (!teamForm.dataReleaseId || !teamForm.rulesetId) throw new Error('队伍必须绑定规则和数据版本')
    if (!members.length || members.length > 6 || members.some(member => !Number.isSafeInteger(member.formId) || member.formId <= 0)) {
      throw new Error('请填写 1–6 个有效的 formId')
    }
    const ids = members.map(member => member.formId)
    if (new Set(ids).size !== ids.length) throw new Error('队伍不能重复使用同一 formId')
    teamsState.status = 'saving'
    const payload = {
      name: teamForm.name,
      rulesetId: teamForm.rulesetId,
      dataReleaseId: teamForm.dataReleaseId,
      members
    }
    const result = isEditingTeam.value
      ? await updatePokemonTeam(editingTeamId.value, payload, {
        idempotencyKey: makeIdempotencyKey('workbench-team'), signal: controller.signal
      })
      : await createPokemonTeam(payload, {
        idempotencyKey: makeIdempotencyKey('workbench-team'), signal: controller.signal
      })
    if (requestWasSuperseded(controller)) return
    const saved = result?.id || result?.teamId ? result : null
    if (saved) teams.value = [saved, ...teams.value.filter(team => (team.id || team.teamId) !== (saved.id || saved.teamId))]
    await loadTeams()
    if (requestWasSuperseded(controller)) return
    resetTeamForm()
  } catch (error) {
    if (isAbortError(error) || requestWasSuperseded(controller)) return
    classifyError(teamsState, error, '队伍保存失败')
    teamFormError.value = error?.message || '队伍保存失败'
  } finally {
    if (teamMutationRequestController === controller) teamMutationRequestController = null
    endRequest(controller)
  }
}

function editTeam(team) {
  const id = team?.id || team?.teamId
  if (!id) return
  editingTeamId.value = id
  teamForm.name = team.name || ''
  teamForm.rulesetId = team.rulesetId || context.rulesetId
  teamForm.dataReleaseId = team.dataReleaseId || context.dataReleaseId
  const members = Array.isArray(team.members) ? team.members : []
  teamForm.members = Array.from({ length: 6 }, (_, index) => {
    const member = members.find(value => Number(value.slot) === index + 1)
    return member?.formId ? String(member.formId) : ''
  })
  teamFormError.value = ''
  teamsState.error = ''
  if (typeof document !== 'undefined') document.querySelector('.team-form')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

function cancelEditTeam() {
  resetTeamForm()
  teamFormError.value = ''
}

function resetTeamForm() {
  editingTeamId.value = null
  teamForm.name = ''
  teamForm.rulesetId = context.rulesetId
  teamForm.dataReleaseId = context.dataReleaseId
  teamForm.members = ['', '', '', '', '', '']
}

async function removeTeam(team) {
  const id = team?.id || team?.teamId
  if (!id) return
  if (typeof window !== 'undefined' && !window.confirm(`确认删除队伍“${team.name || id}”吗？`)) return
  const controller = beginRequest(teamMutationRequestController)
  teamMutationRequestController = controller
  teamsState.status = 'saving'
  teamsState.error = ''
  try {
    await deletePokemonTeam(id, { signal: controller.signal })
    if (requestWasSuperseded(controller)) return
    if (editingTeamId.value === id) resetTeamForm()
    teams.value = teams.value.filter(value => (value.id || value.teamId) !== id)
    markCollection(teamsState, teams.value)
  } catch (error) {
    if (isAbortError(error) || requestWasSuperseded(controller)) return
    classifyError(teamsState, error, '队伍删除失败')
  } finally {
    if (teamMutationRequestController === controller) teamMutationRequestController = null
    endRequest(controller)
  }
}

function favoriteTypeLabel(resourceType) {
  const key = String(resourceType || '').trim().toLowerCase()
  return favoriteTypeLabels[key] || key || '未指定类型'
}

async function loadFavorites({ append = false } = {}) {
  if (append && (!favoriteCursor.value || favoritesLoadingMore.value)) return
  const controller = beginRequest(favoritesRequestController)
  favoritesRequestController = controller
  if (append) {
    favoritesLoadingMore.value = true
    favoritesState.error = ''
  } else {
    favoritesLoadingMore.value = false
    favoritesState.status = 'loading'
    favoritesState.error = ''
    favoriteCursor.value = null
  }
  try {
    const result = await fetchFavorites({
      limit: 50,
      cursor: append ? favoriteCursor.value : undefined,
      signal: controller.signal
    })
    if (requestWasSuperseded(controller)) return
    const page = resourcePage(result)
    favorites.value = append ? [...favorites.value, ...page.items] : page.items
    favoriteCursor.value = page.nextCursor
    markCollection(favoritesState, favorites.value)
  } catch (error) {
    if (isAbortError(error) || requestWasSuperseded(controller)) return
    if (append) {
      favoritesState.error = error?.message || '加载更多收藏失败'
      // 保留已经展示的列表和游标，让用户可以安全重试。
    } else {
      favorites.value = []
      classifyError(favoritesState, error, '收藏读取失败')
    }
  } finally {
    const isCurrentRequest = favoritesRequestController === controller
    if (isCurrentRequest) favoritesRequestController = null
    endRequest(controller)
    if (isCurrentRequest) favoritesLoadingMore.value = false
  }
}

function resetFavoriteForm() {
  favoriteForm.resourceType = 'pokemon'
  favoriteForm.resourceKey = ''
  favoriteForm.label = ''
  favoriteFormError.value = ''
}

async function saveFavorite() {
  const controller = beginRequest(favoriteMutationRequestController)
  favoriteMutationRequestController = controller
  favoriteFormError.value = ''
  try {
    const resourceType = String(favoriteForm.resourceType || '').trim().toLowerCase()
    const resourceKey = String(favoriteForm.resourceKey || '').trim()
    const label = String(favoriteForm.label || '').trim()
    if (!favoriteTypeLabels[resourceType]) throw new Error('请选择受支持的收藏资源类型')
    if (!resourceKey || resourceKey.length > 256) throw new Error('资源键不能为空且不能超过 256 个字符')
    if (label.length > 256) throw new Error('收藏备注不能超过 256 个字符')
    favoritesState.status = 'saving'
    favoritesState.error = ''
    const result = await createFavorite({
      resourceType,
      resourceKey,
      ...(label ? { label } : {})
    }, {
      idempotencyKey: makeIdempotencyKey('workbench-favorite'),
      signal: controller.signal
    })
    if (requestWasSuperseded(controller)) return
    const saved = result && typeof result === 'object' ? result : null
    if (saved && (saved.id || saved.favoriteId)) {
      favorites.value = [saved, ...favorites.value.filter(item =>
        (item.id || item.favoriteId) !== (saved.id || saved.favoriteId))]
    }
    // 重新从服务端取第一页，确保 duplicate/owner/排序等业务语义以服务端为准。
    await loadFavorites()
    if (requestWasSuperseded(controller)) return
    resetFavoriteForm()
  } catch (error) {
    if (isAbortError(error) || requestWasSuperseded(controller)) return
    classifyError(favoritesState, error, '收藏保存失败')
    favoriteFormError.value = error?.message || '收藏保存失败'
  } finally {
    if (favoriteMutationRequestController === controller) favoriteMutationRequestController = null
    endRequest(controller)
  }
}

async function removeFavorite(favorite) {
  const id = favorite?.id || favorite?.favoriteId
  if (!id) return
  if (typeof window !== 'undefined' && !window.confirm(`确认删除收藏“${favorite.label || favorite.resourceKey || id}”吗？`)) return
  const controller = beginRequest(favoriteMutationRequestController)
  favoriteMutationRequestController = controller
  favoritesState.status = 'saving'
  favoritesState.error = ''
  try {
    await deleteFavoriteResource(id, { signal: controller.signal })
    if (requestWasSuperseded(controller)) return
    favorites.value = favorites.value.filter(item => (item.id || item.favoriteId) !== id)
    markCollection(favoritesState, favorites.value)
  } catch (error) {
    if (isAbortError(error) || requestWasSuperseded(controller)) return
    classifyError(favoritesState, error, '收藏删除失败')
  } finally {
    if (favoriteMutationRequestController === controller) favoriteMutationRequestController = null
    endRequest(controller)
  }
}

async function loadActivities() {
  const controller = beginRequest(activitiesRequestController)
  activitiesRequestController = controller
  activitiesState.status = 'loading'
  activitiesState.error = ''
  try {
    const result = await fetchPokemonActivities({
      gameReleaseId: context.gameReleaseId,
      dlcId: context.dlcId || undefined,
      language: context.language,
      limit: 50,
      signal: controller.signal
    })
    if (requestWasSuperseded(controller)) return
    activities.value = resourceItems(result)
    markCollection(activitiesState, activities.value)
  } catch (error) {
    if (isAbortError(error) || requestWasSuperseded(controller)) return
    activities.value = []
    classifyError(activitiesState, error, '活动资讯读取失败')
  } finally {
    if (activitiesRequestController === controller) activitiesRequestController = null
    endRequest(controller)
  }
}

async function loadConversations({ append = false } = {}) {
  if (append && (!conversationCursor.value || conversationsLoadingMore.value)) return
  const controller = beginRequest(conversationsRequestController)
  conversationsRequestController = controller
  if (append) {
    conversationsLoadingMore.value = true
  } else {
    // 完整刷新会取代“加载更多”请求；清掉其 loading 标志，避免旧请求
    // 被 abort 后按钮永久停留在“加载中”。
    conversationsLoadingMore.value = false
    historyState.status = 'loading'
    historyState.error = ''
    conversationCursor.value = null
  }
  try {
    const result = await fetchConversations({
      limit: 50,
      cursor: append ? conversationCursor.value : undefined,
      signal: controller.signal
    })
    if (requestWasSuperseded(controller)) return
    const page = resourcePage(result)
    conversations.value = append ? [...conversations.value, ...page.items] : page.items
    conversationCursor.value = page.nextCursor
    historyState.status = conversations.value.length ? 'ready' : 'empty'
    if (!conversations.value.length) {
      selectedConversation.value = null
      messages.value = []
      messagesState.status = 'idle'
    }
  } catch (error) {
    if (isAbortError(error) || requestWasSuperseded(controller)) return
    if (append) {
      historyState.error = error?.message || '加载更多会话失败'
      // 保留已有列表和游标；用户可在列表中再次重试，而不会把可见历史
      // 误清空成“没有会话”。
    } else {
      conversations.value = []
      classifyError(historyState, error, '历史读取失败')
    }
  } finally {
    const isCurrentRequest = conversationsRequestController === controller
    if (isCurrentRequest) conversationsRequestController = null
    endRequest(controller)
    if (isCurrentRequest) conversationsLoadingMore.value = false
  }
}

async function openConversation(conversation) {
  if (!conversation?.id) return
  const controller = beginRequest(messagesRequestController)
  messagesRequestController = controller
  const conversationId = String(conversation.id)
  selectedConversation.value = conversation
  messages.value = []
  messagesState.status = 'loading'
  messagesState.error = ''
  try {
    const result = await fetchConversationMessages(conversationId, { signal: controller.signal })
    // 用户可能在请求返回前切换到另一会话；旧响应不能覆盖当前选择。
    if (requestWasSuperseded(controller)
      || String(selectedConversation.value?.id || '') !== conversationId) return
    messages.value = Array.isArray(result) ? result : resourceItems(result)
    messagesState.status = messages.value.length ? 'ready' : 'empty'
  } catch (error) {
    if (isAbortError(error) || requestWasSuperseded(controller)
      || String(selectedConversation.value?.id || '') !== conversationId) return
    classifyError(messagesState, error, '消息读取失败')
  } finally {
    if (messagesRequestController === controller) messagesRequestController = null
    endRequest(controller)
  }
}

async function reloadAll() {
  isRefreshing.value = true
  try {
    await Promise.allSettled([loadTeams(), loadActivities(), loadFavorites(), loadConversations()])
  } finally {
    isRefreshing.value = false
  }
}

function makeIdempotencyKey(prefix = 'workbench') {
  try {
    if (globalThis.crypto?.randomUUID) return `${prefix}-${globalThis.crypto.randomUUID()}`
  } catch {
    // 旧浏览器没有 randomUUID 时使用不含敏感数据的时间/随机后缀。
  }
  return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

onMounted(reloadAll)

onBeforeUnmount(() => {
  disposed = true
  for (const controller of pendingControllers) controller.abort()
  pendingControllers.clear()
  factsRequestController = null
  calculationRequestController = null
  teamsRequestController = null
  teamMutationRequestController = null
  activitiesRequestController = null
  favoritesRequestController = null
  favoriteMutationRequestController = null
  conversationsRequestController = null
  messagesRequestController = null
})
</script>

<style scoped>
.workbench-page { min-height: 100vh; background: linear-gradient(180deg, #0f0f1a 0%, #15152a 100%); color: #e7e7f0; }
.workbench-header { display: flex; align-items: center; gap: 16px; padding: 18px 28px; border-bottom: 1px solid rgba(255,255,255,.08); background: rgba(255,255,255,.03); }
.back-btn, .refresh-btn { border: 1px solid rgba(255,255,255,.12); border-radius: 8px; padding: 7px 12px; color: rgba(255,255,255,.78); background: rgba(255,255,255,.06); cursor: pointer; }
.back-btn:hover, .refresh-btn:hover:not(:disabled) { background: rgba(255,255,255,.13); color: #fff; }
.header-copy { min-width: 0; }
.header-copy h1 { margin: 0; font-size: 22px; background: linear-gradient(135deg, #ffcb05, #ff6b9d); -webkit-background-clip: text; -webkit-text-fill-color: transparent; }
.header-copy p { margin: 5px 0 0; color: rgba(255,255,255,.5); font-size: 12px; }
.header-actions { display: flex; align-items: center; gap: 10px; margin-left: auto; }
.mode-badge, .context-badge, .card-kind { color: #95de64; font-size: 11px; }
.mode-badge { padding: 5px 9px; border: 1px solid rgba(149,222,100,.25); border-radius: 999px; background: rgba(149,222,100,.07); }
.refresh-btn:disabled, button:disabled { cursor: not-allowed; opacity: .5; }
.workbench-content { max-width: 1440px; margin: 0 auto; padding: 20px 24px 48px; }
.panel { border: 1px solid rgba(255,255,255,.09); border-radius: 14px; background: rgba(255,255,255,.035); box-shadow: 0 10px 30px rgba(0,0,0,.12); }
.trust-banner { display: flex; gap: 12px; align-items: flex-start; margin-bottom: 16px; padding: 11px 14px; border: 1px solid rgba(105,192,255,.2); border-radius: 10px; color: rgba(255,255,255,.67); background: rgba(105,192,255,.07); font-size: 12px; line-height: 1.6; }
.trust-banner strong { flex: 0 0 auto; color: #69c0ff; }
.context-panel { padding: 18px; margin-bottom: 16px; }
.panel-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; margin-bottom: 15px; }
.panel-heading.compact { margin-bottom: 12px; }
.panel-heading h2 { margin: 0; font-size: 16px; color: #fff; }
.panel-heading p { margin: 5px 0 0; color: rgba(255,255,255,.48); font-size: 12px; line-height: 1.5; }
.context-form { display: grid; grid-template-columns: repeat(6, minmax(120px, 1fr)); gap: 10px; align-items: end; }
label, legend { color: rgba(255,255,255,.62); font-size: 11px; }
label { display: flex; flex-direction: column; gap: 5px; }
input, select { min-width: 0; height: 34px; border: 1px solid rgba(255,255,255,.13); border-radius: 7px; padding: 0 9px; color: #e7e7f0; background: #1a1a30; font: inherit; }
input:focus, select:focus { outline: 2px solid rgba(105,192,255,.45); outline-offset: 1px; }
.context-submit { height: 34px; }
.context-note { margin: 12px 0 0; color: rgba(255,255,255,.38); font-size: 11px; }
.workbench-grid { display: grid; grid-template-columns: minmax(0, 1.15fr) minmax(0, .85fr); gap: 16px; }
.facts-panel, .team-panel, .calculation-panel, .activity-panel, .favorites-panel, .history-panel { padding: 17px; min-width: 0; }
.calculation-panel { grid-column: 1 / -1; }
.activity-panel, .favorites-panel, .history-panel { grid-column: 1 / -1; }
.card-kind { margin-left: 6px; font-weight: 400; opacity: .8; }
.state-chip { flex: 0 0 auto; padding: 4px 8px; border-radius: 6px; color: rgba(255,255,255,.45); background: rgba(255,255,255,.07); font-size: 10px; }
.state-chip.ready { color: #95de64; background: rgba(149,222,100,.12); }
.state-chip.loading, .state-chip.saving { color: #69c0ff; background: rgba(105,192,255,.12); }
.state-chip.empty { color: #faad14; background: rgba(250,173,20,.12); }
.state-chip.permission, .state-chip.error { color: #ff7875; background: rgba(255,77,79,.12); }
.inline-form { display: flex; align-items: end; gap: 9px; margin-bottom: 12px; }
.inline-form .grow { flex: 1; }
.calculation-form { display: grid; grid-template-columns: minmax(150px, .35fr) minmax(220px, 1fr) auto; align-items: end; gap: 10px; margin-bottom: 12px; }
.calculation-defend { min-width: 0; }
.number-field { width: 105px; }
button { font: inherit; }
.primary-btn, .secondary-btn { border: 0; border-radius: 7px; padding: 8px 13px; color: #fff; cursor: pointer; white-space: nowrap; }
.primary-btn { background: linear-gradient(135deg, #667eea, #764ba2); }
.secondary-btn { background: rgba(105,192,255,.18); color: #91d5ff; }
.primary-btn:hover:not(:disabled), .secondary-btn:hover:not(:disabled) { filter: brightness(1.16); }
.state-box { padding: 18px 12px; border-radius: 8px; color: rgba(255,255,255,.55); background: rgba(255,255,255,.035); font-size: 12px; line-height: 1.6; }
.state-box.loading { color: #91d5ff; background: rgba(105,192,255,.07); }
.state-box.permission, .state-box.error { color: #ffaaa5; background: rgba(255,77,79,.07); }
.state-box.empty { color: #ffd591; background: rgba(250,173,20,.06); }
.state-box.idle { color: rgba(255,255,255,.4); }
.state-box button { margin-left: 8px; border: 0; color: #91d5ff; background: none; text-decoration: underline; cursor: pointer; }
.fact-list, .team-list, .activity-list { display: grid; gap: 10px; }
.calculation-card { padding: 12px; border: 1px solid rgba(149,222,100,.22); border-radius: 10px; background: rgba(149,222,100,.06); }
.calculation-value-row { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; color: rgba(255,255,255,.6); font-size: 12px; }
.calculation-value-row strong { color: #b7eb8f; font-size: 26px; }
.calculation-note { margin: 9px 0 0; color: rgba(255,255,255,.4); font-size: 10px; line-height: 1.5; }
.fact-card, .team-card, .activity-card { padding: 12px; border: 1px solid rgba(255,255,255,.08); border-radius: 10px; background: rgba(0,0,0,.14); }
.fact-title-row, .team-card-heading, .activity-heading { display: flex; justify-content: space-between; align-items: flex-start; gap: 10px; }
.fact-card h3, .team-card h3, .activity-card h3 { margin: 0; color: #fff; font-size: 14px; }
.muted { color: rgba(255,255,255,.42); font-size: 11px; }
.type-tags { display: flex; gap: 5px; }
.type-tag { padding: 3px 7px; border-radius: 5px; color: #ffd666; background: rgba(250,173,20,.12); font-size: 10px; }
.stats-grid { display: grid; grid-template-columns: repeat(6, 1fr); gap: 5px; margin: 12px 0; }
.stats-grid div { padding: 6px 3px; text-align: center; border-radius: 6px; background: rgba(255,255,255,.05); }
.stats-grid dt { color: rgba(255,255,255,.4); font-size: 10px; }
.stats-grid dd { margin: 3px 0 0; color: #fff; font-size: 13px; font-weight: 600; }
.lineage-block, .activity-meta { display: flex; flex-wrap: wrap; gap: 5px 12px; color: rgba(255,255,255,.5); font-size: 10px; }
code { color: #b7eb8f; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; }
.evidence-block { display: flex; flex-wrap: wrap; gap: 4px 8px; margin-top: 8px; padding-top: 8px; border-top: 1px solid rgba(255,255,255,.06); color: rgba(255,255,255,.5); font-size: 10px; }
.evidence-block a, .source-link { color: #69c0ff; overflow-wrap: anywhere; }
.evidence-text { flex-basis: 100%; color: rgba(255,255,255,.4); }
.team-form { display: grid; gap: 10px; margin-bottom: 12px; }
.two-fields { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
fieldset { min-width: 0; padding: 8px; border: 1px solid rgba(255,255,255,.1); border-radius: 8px; }
legend { padding: 0 4px; }
.member-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 7px; }
.member-grid label { font-size: 10px; }
.member-grid input { width: 100%; }
.form-error { margin: 0; color: #ffaaa5; font-size: 11px; }
.team-actions { display: flex; align-items: center; gap: 10px; }
.favorite-heading-actions { display: flex; align-items: center; gap: 8px; flex: 0 0 auto; }
.refresh-link { border: 0; padding: 3px 5px; color: #91d5ff; background: none; cursor: pointer; font-size: 11px; text-decoration: underline; }
.refresh-link:disabled { cursor: wait; opacity: .55; }
.favorite-form { display: grid; grid-template-columns: minmax(130px, .25fr) minmax(220px, 1fr) minmax(180px, .6fr) auto; align-items: end; gap: 9px; margin-bottom: 10px; }
.favorite-form .grow { min-width: 0; }
.favorite-list { display: grid; gap: 8px; }
.favorite-card { padding: 10px 11px; border: 1px solid rgba(255,255,255,.08); border-radius: 9px; background: rgba(0,0,0,.14); }
.favorite-card-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; }
.favorite-card h3 { margin: 0; color: #fff; font-size: 13px; }
.favorite-meta { display: block; margin-top: 6px; color: rgba(255,255,255,.35); font-size: 10px; }
.edit-link, .danger-link { border: 0; background: none; cursor: pointer; font-size: 11px; }
.edit-link { color: #91d5ff; }
.danger-link { color: #ffaaa5; }
.team-form-actions { display: flex; align-items: center; gap: 8px; }
.member-list { margin: 10px 0 0; padding-left: 18px; color: rgba(255,255,255,.56); font-size: 11px; line-height: 1.7; }
.activity-card p { margin: 9px 0; color: rgba(255,255,255,.67); font-size: 12px; line-height: 1.6; }
.fresh-badge { padding: 4px 7px; border-radius: 5px; color: #95de64; background: rgba(149,222,100,.1); font-size: 10px; }
.activity-meta { margin-bottom: 8px; }
.source-link { font-size: 11px; }
.history-layout { display: grid; grid-template-columns: minmax(180px, .32fr) minmax(0, .68fr); gap: 12px; min-height: 180px; }
.conversation-list { display: flex; flex-direction: column; gap: 6px; max-height: 360px; overflow: auto; }
.conversation-item { display: flex; flex-direction: column; align-items: flex-start; gap: 3px; padding: 9px; border: 1px solid rgba(255,255,255,.08); border-radius: 8px; color: rgba(255,255,255,.68); background: rgba(255,255,255,.035); text-align: left; cursor: pointer; }
.conversation-item:hover, .conversation-item.selected { border-color: rgba(105,192,255,.4); background: rgba(105,192,255,.1); }
.conversation-item strong { max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; }
.conversation-item small { color: rgba(255,255,255,.4); font-size: 10px; }
.load-more-btn { padding: 7px 9px; border: 1px solid rgba(105,192,255,.2); border-radius: 7px; color: #91d5ff; background: rgba(105,192,255,.06); cursor: pointer; font-size: 11px; }
.load-more-btn:disabled { cursor: wait; opacity: .6; }
.history-inline-error { margin: 2px 0 0; color: #ffaaa5; font-size: 10px; line-height: 1.5; }
.history-inline-error button { margin-left: 5px; padding: 0; border: 0; color: #91d5ff; background: none; text-decoration: underline; cursor: pointer; font: inherit; }
.message-history { min-width: 0; }
.message-list { display: flex; flex-direction: column; gap: 8px; max-height: 360px; margin: 0; padding: 0; overflow: auto; list-style: none; }
.history-message { padding: 9px 11px; border-radius: 8px; background: rgba(255,255,255,.045); }
.history-message.user { margin-left: 12%; background: rgba(102,126,234,.18); }
.history-message.assistant { margin-right: 12%; }
.message-role { color: #91d5ff; font-size: 10px; }
.history-message p { margin: 4px 0; color: rgba(255,255,255,.78); white-space: pre-wrap; word-break: break-word; font-size: 12px; line-height: 1.6; }
.history-message small { color: rgba(255,255,255,.35); font-size: 10px; }
@media (max-width: 1050px) { .context-form { grid-template-columns: repeat(3, 1fr); } .workbench-grid { grid-template-columns: 1fr; } .calculation-panel, .activity-panel, .favorites-panel, .history-panel { grid-column: auto; } .favorite-form { grid-template-columns: 1fr 1fr; } }
@media (max-width: 650px) { .workbench-header { align-items: flex-start; padding: 14px 16px; } .header-actions { flex-direction: column; align-items: flex-end; } .mode-badge { display: none; } .workbench-content { padding: 14px 12px 32px; } .context-form { grid-template-columns: 1fr 1fr; } .calculation-form { grid-template-columns: 1fr; } .favorite-form { grid-template-columns: 1fr; } .inline-form { flex-wrap: wrap; } .inline-form .grow { flex-basis: 100%; } .number-field { flex: 1; width: auto; } .history-layout { grid-template-columns: 1fr; } .stats-grid { grid-template-columns: repeat(3, 1fr); } }
</style>
