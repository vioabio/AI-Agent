export function stage4Cases() {
 const main=[
  '朱紫有哪些道馆馆主？请编号列出。',
  '看看你刚才列表中第四位馆主的队伍。',
  '我的 test1 队伍能应对这位馆主吗？',
  '根据我实际保存的招式，建议怎么出招？',
  '再看第六位馆主，需要调整哪些成员？',
  '你刚才说的调整已经保存到我的队伍了吗？'
 ]
 const filler='收到，先保留目前的馆主和队伍背景。这一轮仅回复“收到”，不要修改或保存队伍。'
 const groups=[
  {id:'S4-A',turns:[...main,...Array(9).fill(filler),'再用我的 test1 队伍分析这个馆主，按已保存招式给建议。','这个队伍的等级和两招是什么？'],
   turnCaseIds:[...main.map((_,i)=>'S4-A0'+(i+1)),...Array.from({length:9},(_,i)=>'S4-L0'+(i+1)),'S4-L10','S4-L11']},
  {id:'S4-B',turns:['看看阿枫初战的队伍。','改看奇树，不是阿枫。','我有8枚徽章，但还没完成主线，能打这位馆主的视察重赛吗？'],turnCaseIds:['S4-B01','S4-B02','S4-B03']},
  {id:'S4-C',turns:['这个馆主怎么打？'],turnCaseIds:['S4-C01']},
  {id:'S4-D',turns:['请看海岱初战，再读取我的test1队伍，只分析不修改。','我说错了，目标是莱姆，不是海岱，还是刚才那支队伍。','先不聊队伍，解释一下种族值和努力值的区别。','回到刚才的莱姆，我现有两招能做什么？没有证据的机制不要猜。'],turnCaseIds:['S4-D01','S4-D02','S4-D03','S4-D04']}
 ]
 return groups.map(group=>({...group,split:'dev',scope:{gameReleaseId:'pokemon-scarlet-violet',battleFormat:'casual-singles',language:'zh-CN',rulesetVersionId:'casual-v1'}}))
}
