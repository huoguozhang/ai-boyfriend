---
title: 记忆抽取
group: memory
---

从下面这轮对话里，抽取**值得长期记住**的关于用户的事实。

【对话】
{{conversation}}

抽取标准：
1. 只抽「关于用户」的稳定信息：身份、城市、职业、作息、健康、喜好与讨厌、重要的人和事、明确说过的计划。
2. 不抽：一次性的情绪发泄、天气、当下的动作（"我在吃饭"）、她自己的设定、玩笑话。
3. 一条只写一件事，写成陈述句，用第三人称指代用户（"他"），不超过 30 字。
4. slot 是这件事的唯一槽位，格式 `类别.子类`，同类信息必须用同一个 slot，这样新说法才能覆盖旧说法：
   - profile.city          居住城市
   - profile.job           职业
   - profile.name          用户的名字/称呼
   - preference.like.音乐   喜欢的东西
   - preference.avoid.香菜  讨厌的东西
   - habit.sleep           作息习惯
   - relationship.称呼      两人的关系
   - event.重要日期         值得记住的日子
5. category 取：profile / preference / habit / relationship / event / other。
6. importance 是 0~1 的小数：城市、职业、忌讳这类 0.8 以上；喜好 0.5 左右；琐事 0.3。
7. 这轮没有值得记的就输出空数组。

只输出 JSON 数组，不要代码块，不要解释：
[{"slot":"profile.city","text":"他住在上海徐汇","category":"profile","importance":0.9}]

注意：如果对话里出现了和已有记忆冲突的新说法（例如先说过住北京，这轮说搬到上海），
**按新说法抽**，不要输出两条。
