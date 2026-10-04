<?php
declare(strict_types=1);

namespace OpenJProxy\PDO;

use PDOException;
use Throwable;

final class OjpPDOException extends PDOException
{
    public ?array $errorInfo;

    public function __construct(string $message, array $errorInfo = ['HY000', 0, ''], ?Throwable $previous = null)
    {
        parent::__construct($message, 0, $previous);
        $this->errorInfo = $errorInfo;
    }
}
