# Projeto Bully (Cliente-Servidor em Java)

Projeto universitário desenvolvido em conjunto com colegas de turma: Igor Rochelle e Lucas Righetto, com propósito de estudo sobre conexão entre aplicação cliente-servidor utilizando Java. O programa simula uma interface de usina para controle de monstro (temperatura e pH), com foco na aplicação prática do algoritmo de Bully (eleição de coordenador).

## Visão geral
- Arquitetura cliente-servidor via TCP.
- Simulação de painel de controle (temperatura e pH).
- Implementação do algoritmo de Bully para eleição de coordenador.

## Estrutura do projeto
- `src/projBully/`: código-fonte Java.
- `build/` e `nbproject/`: artefatos e configurações do NetBeans/Ant.
- `test/`: testes (se aplicável).

## Principais classes
- `Bully_TCPServerAtivosMain`: inicialização do servidor de ativos.
- `Bully_TCPServerAtivosHandler`: processamento das conexões ativas.
- `Bully_TCPServerConnection`: controle de conexão TCP.
- `Bully_TCPClientMain`: inicialização do cliente.
- `Bully_TCPClientHandler`: processamento do cliente.
- `BullyServer` / `BullyClient1`: interface gráfica.

## Como executar
1. Abra o projeto no NetBeans (Ou VSCode).
2. Execute o servidor (classe principal do servidor).
3. Execute o cliente (classe principal do cliente).

> Observação: os nomes das classes principais podem variar conforme a configuração do NetBeans. Verifique a configuração de execução no IDE.

## Autores
- Matheus Hortiz
- Igor Rochelle
- Lucas Righetto
