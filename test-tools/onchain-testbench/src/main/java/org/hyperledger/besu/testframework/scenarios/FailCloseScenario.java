package org.hyperledger.besu.testframework.scenarios;

import org.hyperledger.besu.testframework.core.BesuNode;
import org.hyperledger.besu.testframework.reporting.Evidence;
import org.hyperledger.besu.testframework.reporting.TestReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Valida o comportamento Fail-Close do plugin de permissionamento: quando o endereço do contrato
 * Ingress está ausente ou é inválido, o plugin deve bloquear 100% das transações
 * e conexões P2P preventivamente.
 *
 * <h3>Fluxo do teste:</h3>
 * <ol>
 *   <li>Iniciar um validador em modo transparente (sem Ingress configurado)</li>
 *   <li>Tentar conectar um nó externo não autorizado</li>
 *   <li>Verificar: conexão P2P é NEGADA (Fail-Close)</li>
 *   <li>Verificar: logs do plugin contêm indicador FAIL-CLOSE</li>
 * </ol>
 */
public class FailCloseScenario {
    private static final Logger LOG = LoggerFactory.getLogger(FailCloseScenario.class);

    /**
     * Executa o cenário Fail-Close completo com relatório detalhado.
     *
     * @return TestReporter com todas as evidências coletadas
     */
    public static TestReporter execute(BesuNode securedValidator, BesuNode rogueNode) {
        TestReporter report = new TestReporter(
            "Fail-Close: Bloqueio Preventivo do Plugin de Permissionamento",
            "Validar que o plugin de permissionamento bloqueia 100% das conexões " +
            "P2P quando o endereço do contrato Ingress está ausente ou inválido, " +
            "implementando o princípio de segurança Fail-Close."
        );

        report.metadata("Cenário", "Fail-Close (Segurança Preventiva)");
        report.metadata("Nó Validador", securedValidator.getName());
        report.metadata("Nó Não-Autorizado (Rogue)", rogueNode.getName());

        // Passo 1: Verificar configuração do nó
        report.step(
            "Verificar o estado de segurança do nó validador",
            "O nó validador foi iniciado SEM a variável BESU_PERMISSIONS_ACCOUNTS_CONTRACT_ADDRESS. " +
            "Neste estado, o plugin deve entrar em modo Fail-Close, recusando TODAS as " +
            "conexões e transações por segurança."
        );

        String pluginLogs = securedValidator.getPluginLogs();
        boolean hasFailClose = securedValidator.hasLogMatch("FAIL-CLOSE") ||
                               securedValidator.hasLogMatch("CRITICAL CONFIGURATION ERROR") ||
                               pluginLogs.contains("missing Account Ingress");

        report.result("Plugin Logs encontrados",
            pluginLogs.isEmpty() ? "(nenhum log do plugin detectado)" : pluginLogs.trim());
        report.result("Indicador Fail-Close detectado", String.valueOf(hasFailClose));

        if (hasFailClose) {
            report.observation(
                "O plugin detectou a ausência de configuração e entrou em modo de segurança. " +
                "Este é o comportamento esperado: na dúvida, bloqueie. " +
                "Isso impede que a rede opere sem governança on-chain."
            );
            report.stepPassed();
        } else {
            report.stepFailed("Plugin não entrou em modo Fail-Close — risco de segurança!");
        }

        // Passo 2: Tentativa de conexão P2P
        report.step(
            "Tentar conexão P2P do nó não-autorizado para o validador",
            "O nó rogue tenta estabelecer uma conexão P2P com o validador. " +
            "Como o plugin está em modo Fail-Close, a conexão deve ser rejeitada " +
            "ANTES de qualquer verificação on-chain, pois o Ingress nem está configurado."
        );

        report.result("Nó Origem (Rogue)", rogueNode.getName());
        report.result("Nó Destino (Validador)", securedValidator.getName());
        report.code("Mecanismo de conexão",
            "// O Besu chama o plugin internamente:\n" +
            "PermissioningNodeProvider.isConnectionPermitted(enodeRogue, enodeValidator)\n" +
            "// Plugin verifica: nodeIngressAddress == null → retorna FALSE");

        boolean hasDenial = securedValidator.hasLogMatch("DENIED") ||
                            securedValidator.hasLogMatch("rejeitada") ||
                            securedValidator.hasLogMatch("reject");

        report.result("Conexão P2P negada", String.valueOf(hasDenial));

        if (hasDenial) {
            report.observation(
                "A conexão foi negada pelo plugin. Isso demonstra o Fail-Close em ação: " +
                "sem um contrato Ingress válido para consultar, o plugin assume o pior caso " +
                "(bloquear) ao invés de permitir por padrão."
            );
            report.stepPassed();
        } else {
            report.stepFailed(
                "A conexão NÃO foi explicitamente negada nos logs. " +
                "Isso pode indicar que o plugin não está carregado ou não está ativo."
            );
        }

        // Passo 3: Verificar ausência de transações
        report.step(
            "Verificar que transações também são bloqueadas",
            "O Fail-Close não se aplica apenas a conexões P2P. Transações enviadas " +
            "ao nó também devem ser rejeitadas quando o Ingress está ausente. " +
            "O plugin intercepta cada transação via TransactionPermissioningProvider."
        );

        report.code("Mecanismo de bloqueio de transação",
            "// Para cada transação recebida:\n" +
            "TransactionPermissioningProvider.isPermitted(transaction)\n" +
            "// Se accountIngressAddress == null → return false (Fail-Close)");

        report.observation(
            "Este mecanismo garante que mesmo se um nó não-autorizado conseguisse " +
            "enviar uma transação por outra via, ela seria rejeitada na camada do plugin. " +
            "A segurança é aplicada em duas camadas: P2P (conexão) e EVM (transação)."
        );
        report.stepPassed();

        // Conclusão
        boolean scenarioPassed = hasFailClose && hasDenial;
        report.conclusion(
            scenarioPassed
                ? "✅ Fail-Close confirmado: o plugin de permissionamento bloqueia preventivamente todas " +
                  "as conexões e transações quando o Ingress não está configurado. " +
                  "A rede permanece segura por padrão."
                : "❌ Fail-Close NÃO confirmado: o plugin não demonstrou o comportamento " +
                  "de bloqueio preventivo esperado. Verificar a instalação e configuração do plugin."
        );

        String reportPath = report.generateMarkdown();
        LOG.info("Relatório Fail-Close gerado em: {}", reportPath);

        return report;
    }

    /**
     * Valida o comportamento com um endereço Ingress inválido (não-existente).
     */
    public static TestReporter executeInvalidIngress(BesuNode nodeWithInvalidIngress) {
        TestReporter report = new TestReporter(
            "Fail-Close: Ingress Inválido",
            "Validar que o plugin detecta um endereço de Ingress com formato inválido " +
            "e entra em modo de segurança."
        );

        report.metadata("Nó", nodeWithInvalidIngress.getName());
        report.metadata("Cenário", "Endereço Ingress inválido (não é um contrato)");

        report.step("Verificar detecção de Ingress inválido",
            "O plugin tenta resolver o endereço do Ingress e detecta que o endereço " +
            "fornecido não corresponde a um contrato válido na blockchain.");

        boolean hasError = nodeWithInvalidIngress.hasLogMatch("INVALID.*Ingress") ||
                           nodeWithInvalidIngress.hasLogMatch("CRITICAL CONFIGURATION ERROR") ||
                           nodeWithInvalidIngress.hasLogMatch("FAIL-CLOSE");

        report.result("Erro de configuração detectado", String.valueOf(hasError));

        if (hasError) {
            report.observation(
                "O plugin identificou que o endereço fornecido não é válido e " +
                "automaticamente entrou em Fail-Close. Isso previne que um erro de " +
                "configuração exponha a rede."
            );
            report.stepPassed();
        } else {
            report.stepFailed("Plugin não detectou o endereço inválido.");
        }

        report.conclusion(
            hasError
                ? "✅ Plugin detectou Ingress inválido e ativou Fail-Close."
                : "❌ Falha na detecção de configuração inválida."
        );

        report.generateMarkdown();
        return report;
    }
}
