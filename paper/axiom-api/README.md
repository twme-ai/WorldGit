# AxiomPaper compileOnly API

最小事件與 CustomIntegration 簽章，來源 [Moulberry/AxiomPaperPlugin](https://github.com/Moulberry/AxiomPaperPlugin/tree/69c1b0a2fff6999dddacfe945c33eccf66877b8d)（MIT；完整授權見 LICENSE）。事件保留來源行為，Integration 只保留編譯所需簽章，不能當作伺服器插件。已以兩版正式 AxiomPaper 6.0.1 jar 的 javap 核對。

此獨立 Gradle 專案只供 paper:common compileOnly，CI 不需下載 Axiom 或建置其 NMS；**不得放進 WorldGit 發佈 jar**。執行期使用選用 AxiomPaper 提供的類別。沒有複製閉源 Fabric Axiom 的程式碼。
